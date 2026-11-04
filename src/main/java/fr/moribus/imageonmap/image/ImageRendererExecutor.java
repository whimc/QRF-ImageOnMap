/*
 * Copyright or © or Copr. Moribus (2013)
 * Copyright or © or Copr. ProkopyL <prokopylmc@gmail.com> (2015)
 * Copyright or © or Copr. Amaury Carrade <amaury@carrade.eu> (2016 – 2022)
 * Copyright or © or Copr. Vlammar <anais.jabre@gmail.com> (2019 – 2024)
 *
 * This software is a computer program whose purpose is to allow insertion of
 * custom images in a Minecraft world.
 *
 * This software is governed by the CeCILL license under French law and
 * abiding by the rules of distribution of free software.  You can  use,
 * modify and/ or redistribute the software under the terms of the CeCILL
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info".
 *
 * As a counterpart to the access to the source code and  rights to copy,
 * modify and redistribute granted by the license, users are provided only
 * with a limited warranty  and the software's author,  the holder of the
 * economic rights,  and the successive licensors  have only  limited
 * liability.
 *
 * In this respect, the user's attention is drawn to the risks associated
 * with loading,  using,  modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software,
 * that may mean  that it is complicated to manipulate,  and  that  also
 * therefore means  that it is reserved for developers  and  experienced
 * professionals having in-depth computer knowledge. Users are therefore
 * encouraged to load and test the software's suitability as regards their
 * requirements in conditions enabling the security of their systems and/or
 * data to be ensured and,  more generally, to use and operate it in the
 * same conditions as regards security.
 *
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL license and that you accept its terms.
 */

package fr.moribus.imageonmap.image;


import fr.moribus.imageonmap.Permissions;
import fr.moribus.imageonmap.PluginConfiguration;
import fr.moribus.imageonmap.map.ImagePoster;
import fr.moribus.imageonmap.map.PosterData;
import fr.moribus.imageonmap.map.PosterManager;
import fr.moribus.imageonmap.map.PosterMap;
import fr.zcraft.quartzlib.components.i18n.I;
import fr.zcraft.quartzlib.components.worker.Worker;
import fr.zcraft.quartzlib.components.worker.WorkerAttributes;
import fr.zcraft.quartzlib.components.worker.WorkerCallback;
import fr.zcraft.quartzlib.components.worker.WorkerRunnable;
import fr.zcraft.quartzlib.tools.PluginLogger;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.InetAddress;
import java.net.Proxy;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;


@WorkerAttributes(name = "Image Renderer", queriesMainThread = true)
public class ImageRendererExecutor extends Worker {
    public static void renderAndNotify(final URL url, final ImageUtils.ScalingType scaling, final UUID playerUUID,
                                       final int width, final int height) {
        final Player player = Bukkit.getPlayer(playerUUID);
        if (player == null) {
            return;
        }
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(I.t("&1 Rendering...")));

        render(url, scaling, player.getUniqueId(), width, height, new WorkerCallback<ImagePoster>() {
            @Override
            public void finished(ImagePoster result) {
                String message = I.t("&1 Rendering finished!");
                TextComponent text = new TextComponent(message);
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, text);

                if (result.give(player) && (result instanceof PosterMap && !((PosterMap) result).hasColumnData())) {
                    player.spigot().sendMessage(ChatMessageType.CHAT,
                            new TextComponent(I.t("&7 The rendered map was too big to fit in your inventory.")));
                    player.spigot().sendMessage(ChatMessageType.CHAT,
                            new TextComponent(I.t("&7 Use '/maptool getremaining' to get the remaining maps.")));
                }
            }

            @Override
            public void errored(Throwable exception) {
                String message = I.t("&C Map rendering failed: {0}", exception.getMessage());
                TextComponent text = new TextComponent(message);
                player.spigot().sendMessage(ChatMessageType.CHAT, text);
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));

                PluginLogger.warning("Rendering from {0} failed: {1}: {2}",
                        player.getName(),
                        exception.getClass().getCanonicalName(),
                        exception.getMessage());
            }
        });
    }

    private static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 16_000_000;

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) {
            return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && (second == 0 || second == 168))
                    && !(first == 198 && (second == 18 || second == 19));
        }
        // Allow global IPv6 unicast only, excluding transition and special-purpose ranges.
        return (first & 0xe0) == 0x20 && !(first == 0x20 && (second == 1 || second == 2));
    }

    private static BufferedImage downloadImage(URL url) throws IOException {
        if (!("http".equalsIgnoreCase(url.getProtocol()) || "https".equalsIgnoreCase(url.getProtocol()))
                || url.getHost().isEmpty() || url.getUserInfo() != null) {
            throw new IOException("Only public HTTP(S) image URLs are allowed.");
        }
        for (InetAddress address : InetAddress.getAllByName(url.getHost())) {
            if (!isPublicAddress(address)) {
                throw new IOException("Image URLs must resolve to public addresses.");
            }
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try {
            int status = connection.getResponseCode();
            if (status / 100 == 3) {
                throw new IOException("Image redirects are disabled. Use a direct image URL.");
            }
            if (status / 100 != 2) {
                throw new IOException("HTTP error: " + status);
            }
            if (connection.getContentLengthLong() > MAX_DOWNLOAD_BYTES) {
                throw new IOException("Image download exceeds 16 MiB.");
            }
            try (InputStream stream = connection.getInputStream()) {
                byte[] data = stream.readNBytes(MAX_DOWNLOAD_BYTES + 1);
                if (data.length > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("Image download exceeds 16 MiB.");
                }
                try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
                    var readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) {
                        return null;
                    }
                    var reader = readers.next();
                    try {
                        reader.setInput(input, true, true);
                        if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_IMAGE_PIXELS) {
                            throw new IOException("Image exceeds 16 million pixels.");
                        }
                        return reader.read(0);
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void checkSizeLimit(final UUID playerUUID, final BufferedImage image) throws IOException {
        if ((PluginConfiguration.LIMIT_SIZE_X.get() > 0 || PluginConfiguration.LIMIT_SIZE_Y.get() > 0)
                && !Permissions.BYPASS_SIZE.grantedTo(Bukkit.getPlayer(playerUUID))) {
            if (PluginConfiguration.LIMIT_SIZE_X.get() > 0) {
                if (image.getWidth() > PluginConfiguration.LIMIT_SIZE_X.get()) {
                    throw new IOException(I.t("The image is too wide!"));
                }
            }
            if (PluginConfiguration.LIMIT_SIZE_Y.get() > 0) {
                if (image.getHeight() > PluginConfiguration.LIMIT_SIZE_Y.get()) {
                    throw new IOException(I.t("The image is too tall!"));
                }
            }
        }
    }

    public static void render(final PosterData posterData, final UUID playerUUID,
                              WorkerCallback<ImagePoster> callback) {
        render(posterData.getURL(), posterData.getScaling(), playerUUID, posterData.getWidth(), posterData.getHeight(),
                callback);
    }

    public static void render(final URL url, final ImageUtils.ScalingType scaling, final UUID playerUUID,
                              final int width, final int height, WorkerCallback<ImagePoster> callback) {
        submitQuery(new WorkerRunnable<ImagePoster>() {

            @Override
            public ImagePoster run() throws Throwable {

                BufferedImage image = null;

                //If the link is an imgur one
                if (url.toString().toLowerCase().startsWith("https://imgur.com/")) {

                    //Not handled, can't with the hash only access the image in i.imgur.com/<hash>.<extension>

                    if (url.toString().contains("gallery/")) {
                        throw new IOException(
                                "We do not support imgur gallery yet, please use direct link to image instead."
                                        + " Right click on the picture you want "
                                        + "to use then select copy picture link:) ");
                    }

                    for (Extension ext : Extension.values()) {
                        String newLink = "https://i.imgur.com/" + url.toString().split("https://imgur.com/")[1]
                                + "."
                                + ext.toString();
                        URL url2 = new URL(newLink);

                        image = downloadImage(url2);

                        //valid image
                        if (image != null) {
                            break;
                        }

                    }

                } else {
                    image = downloadImage(url);
                }
                if (image == null) {
                    throw new IOException(I.t("The given URL is not a valid image"));
                }
                // Limits are in place and the player does NOT have rights to avoid them.
                checkSizeLimit(playerUUID, image);
                final BufferedImage resizedImage;

                resizedImage = scaling.resize(image, ImagePoster.WIDTH * width, ImagePoster.HEIGHT * height);
                image.flush();//Safe to free

                return renderPoster(resizedImage, playerUUID);
            }
        }, callback);
    }

    public static void update(final URL url, final ImageUtils.ScalingType scaling, final UUID playerUUID,
                              final ImagePoster poster, final int width, final int height,
                              WorkerCallback<ImagePoster> callback) {
        submitQuery(new WorkerRunnable<ImagePoster>() {
            @Override
            public ImagePoster run() throws Throwable {
                final BufferedImage image = downloadImage(url);

                if (image == null) {
                    throw new IOException(I.t("The given URL is not a valid image"));
                }

                // Limits are in place and the player does NOT have rights to avoid them.
                checkSizeLimit(playerUUID, image);

                updatePoster(scaling.resize(image, width * 128, height * 128),
                        playerUUID, poster.getPostersIDs());
                return poster;

            }
        }, callback);

    }

    private static void updatePoster(final BufferedImage image, final UUID playerUUID, int[] postersIDs)
            throws Throwable {

        final PosterImage poster = new PosterImage(image);
        poster.splitImages();

        ImageIOExecutor.saveImage(postersIDs, poster);

        if (PluginConfiguration.SAVE_FULL_IMAGE.get()) {
            ImageIOExecutor.saveImage(ImagePoster.getFullImageFile(postersIDs[0], postersIDs[postersIDs.length - 1]),
                    image);
        }

        submitToMainThread(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                Renderer.installRenderer(poster, postersIDs);
                return null;
            }
        });
    }

    private static ImagePoster renderPoster(final BufferedImage image, final UUID playerUUID) throws Throwable {
        final PosterImage poster = new PosterImage(image);
        final int posterCount = poster.getImagesCount();
        PosterManager.checkPosterLimit(posterCount, playerUUID);
        final Future<int[]> futurePostersIds = submitToMainThread(new Callable<int[]>() {
            @Override
            public int[] call() throws Exception {
                return PosterManager.getNewPostersIds(posterCount);
            }
        });
        poster.splitImages();
        final int[] postersIDs = futurePostersIds.get();
        ImageIOExecutor.saveImage(postersIDs, poster);


        ImageIOExecutor.saveImage(postersIDs, poster);
        if (PluginConfiguration.SAVE_FULL_IMAGE.get()) {
            ImageIOExecutor.saveImage(ImagePoster.getFullImageFile(postersIDs[0], postersIDs[postersIDs.length - 1]),
                    image);
        }

        submitToMainThread(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                Renderer.installRenderer(poster, postersIDs);
                return null;
            }

        });
        poster.getImage().flush();//Safe to free
        return PosterManager.createPoster(poster, playerUUID, postersIDs);
    }

    private enum Extension {
        png, jpg, jpeg, gif
    }

}
