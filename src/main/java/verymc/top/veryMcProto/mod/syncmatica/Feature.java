package verymc.top.veryMcProto.mod.syncmatica;

/**
 * Syncmatica 协议特性枚举（移植自 {@code ch.endte.syncmatica.Feature}）。
 *
 * <p>握手时双方交换 FeatureSet，决定 metadata / position 包编码哪些可选字段。
 * 其中 4 个直接影响协议字段编码：{@link #DISPLAY_NAME} / {@link #CORE_EX} / {@link #VERSION} / {@link #MODIFY}。
 * 详见 docs/21-syncmatica-protocol.md §3 / §4。
 */
public enum Feature
{
    CORE, // every feature that's part of 0.1.0 - it doesn't make sense to divide those further since compatibility with 0.0 of future versions
    // cannot be maintained and the version is very alpha.
    FEATURE, // the possibility of reporting on ones own features during version exchange
    MODIFY, // commands to modify the placement of a syncmatic placement on the server
    MESSAGE, // ability to send messages to display from server to client
    QUOTA,  // quota on client uploads to the server
    DEBUG,  // ability to configure debugging
    CORE_EX, // extended basic features - such as who owns a placement and subregion sharing
    VERSION, // extended version metadata
    DISPLAY_NAME, // extended file / display name feature for saving and loading files
    ;

    public static Feature fromString(final String s)
    {
        for (final Feature f : Feature.values())
        {
            if (f.toString().equals(s))
            {
                return f;
            }
        }
        return null;
    }
}
