package verymc.top.veryMcProto.mod.syncmatica.communication;

import verymc.top.veryMcProto.mod.syncmatica.Feature;

import java.util.*;

/**
 * Feature 集合（移植自 {@code ch.endte.syncmatica.communication.FeatureSet}）。
 *
 * <p>表示一个 syncmatica 实例支持/启用的特性集。序列化方式：{@code \n} 分隔的 Feature 枚举名字符串
 * （<b>非二进制位图</b>），作为一个 writeUtf/readUtf 字段传输。
 *
 * <p>版本默认集：仅 {@code "0.1" → {CORE}}。其他版本走 FEATURE 交换获取对端实际 FeatureSet。
 * 详见 docs/21-syncmatica-protocol.md §3。
 */
public class FeatureSet {

    private static final Map<String, FeatureSet> versionFeatures;
    private final Collection<Feature> features;

    public static FeatureSet fromVersionString(String version) {
        if (version.matches("^\\d+(\\.\\d+){2,4}$")) {
            final int minSize = version.indexOf(".");
            while (version.length() > minSize) {
                if (versionFeatures.containsKey(version)) {
                    return versionFeatures.get(version);
                }
                final int lastDot = version.lastIndexOf(".");
                version = version.substring(0, lastDot);
            }
        }
        return null;
    }

    public static FeatureSet fromString(final String features) {
        final FeatureSet featureSet = new FeatureSet(new ArrayList<>());
        for (final String feature : features.split("\n")) {
            final Feature f = Feature.fromString(feature);
            if (f != null) {
                featureSet.features.add(f);
            }
        }
        return featureSet;
    }

    @Override
    public String toString() {
        final StringBuilder output = new StringBuilder();
        boolean b = false;
        for (final Feature feature : features) {
            output.append(b ? "\n" + feature.toString() : feature.toString());
            b = true;
        }
        return output.toString();
    }

    public FeatureSet(final Collection<Feature> features) {
        this.features = features;
    }

    public boolean hasFeature(final Feature f) {
        return features.contains(f);
    }

    static {
        versionFeatures = new HashMap<>();
        versionFeatures.put("0.1", new FeatureSet(Collections.singletonList(Feature.CORE)));
    }

}
