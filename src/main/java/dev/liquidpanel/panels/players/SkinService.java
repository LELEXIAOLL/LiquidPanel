package dev.liquidpanel.panels.players;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 玩家皮肤头像。
 *
 * <h2>贴图由浏览器直接加载</h2>
 * <p>服务端只负责把贴图链接解析出来（读玩家 Profile，不走网络），
 * 图片本身交给浏览器去 {@code textures.minecraft.net} 取 ——
 * 走的是管理员自己的网络，比让服务端翻墙去取可靠得多，
 * 而且浏览器自带缓存，不必在这里再维护一份。
 *
 * <h2>默认头像</h2>
 * <p>没有皮肤的玩家（离线模式、未设置皮肤）用 {@link #renderSteve()} 程序化画一张。
 * Minecraft 的皮肤是 64×64 像素图，头部正好是 (8,8)-(16,16) 这 8×8 个像素 ——
 * Steve 的脸全是纯色块，按像素画出来即可，不需要任何外部贴图文件。
 */
public final class SkinService {

    /** Steve 的脸只画一次，之后一直复用 */
    private final byte[] steve = renderSteve();

    /**
     * 默认头像（Steve）。
     *
     * <p>没有皮肤的玩家、以及贴图加载失败的玩家，都回退到这张。
     */
    public byte[] defaultSkin() {
        return steve;
    }

    // ------------------------------------------------------------------
    // 默认皮肤
    // ------------------------------------------------------------------

    /** 头发 */
    private static final int HAIR = 0xFF46281E;
    /** 肤色 */
    private static final int SKIN = 0xFFB5836C;
    /** 眼白 */
    private static final int EYE_WHITE = 0xFFFFFFFF;
    /** 瞳孔 */
    private static final int PUPIL = 0xFF3D2C8D;
    /** 嘴 */
    private static final int MOUTH = 0xFF74513D;

    /**
     * Steve 的 8×8 脸，一行一个像素。H=头发 S=肤色 W=眼白 P=瞳孔 M=嘴
     */
    private static final String[] STEVE_FACE = {
            "HHHHHHHH",
            "HHHHHHHH",
            "HHSSSSHH",
            "SSSSSSSS",
            "SWPSSWPS",
            "SSSSSSSS",
            "SSSSSSSS",
            "SSMMMMSS"
    };

    /**
     * 画一张 Steve 的皮肤。
     *
     * <p>只画头部那 8×8 就够了 —— 面板只显示头像，其余区域留透明。
     */
    private static byte[] renderSteve() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();

        for (int row = 0; row < STEVE_FACE.length; row++) {
            String line = STEVE_FACE[row];
            for (int column = 0; column < line.length(); column++) {
                image.setRGB(8 + column, 8 + row, colorOf(line.charAt(column)));
            }
        }
        graphics.dispose();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            // 理论上不会发生（写内存流），真发生了返回空数组由前端兜底
            return new byte[0];
        }
    }

    private static int colorOf(char code) {
        return switch (code) {
            case 'H' -> HAIR;
            case 'W' -> EYE_WHITE;
            case 'P' -> PUPIL;
            case 'M' -> MOUTH;
            default -> SKIN;
        };
    }
}
