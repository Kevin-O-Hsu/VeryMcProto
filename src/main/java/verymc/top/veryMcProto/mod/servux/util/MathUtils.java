package verymc.top.veryMcProto.mod.servux.util;

import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

/**
 * 数学工具（mod 层）。移植自原版 {@code fi.dy.masa.servux.util.MathUtils}。
 *
 * <p><b>适配</b>：去掉 {@code org.apache.commons.lang3.math.Fraction} 依赖（Paper 运行时不保证暴露），
 * 相应去掉 {@code min/max(Fraction)} 重载；其余照抄。
 */
public class MathUtils
{
    private static final int[] MULTIPLY_DE_BRUIJN_BIT_POSITION = new int[]{0, 1, 28, 2, 29, 14, 24, 3, 30, 22, 20, 15, 25, 17, 4, 8, 31, 27, 13, 23, 21, 19, 16, 7, 26, 12, 18, 6, 11, 5, 10, 9};

    public static double average(int[] values)
    {
        final int size = values.length;
        if (size == 0) { return 0; }
        long sum = 0;
        for (int value : values) { sum += value; }
        return (double) sum / (double) size;
    }

    public static double average(long[] values)
    {
        final int size = values.length;
        if (size == 0) { return 0; }
        long sum = 0;
        for (long value : values) { sum += value; }
        return (double) sum / (double) size;
    }

    public static double average(double[] values)
    {
        final int size = values.length;
        if (size == 0) { return 0; }
        double sum = 0;
        for (double value : values) { sum += value; }
        return sum / (double) size;
    }

    public static int clamp(int value, int min, int max)
    {
        return value < min ? min : Math.min(value, max);
    }

    public static long clamp(long value, long min, long max)
    {
        return value < min ? min : Math.min(value, max);
    }

    public static float clamp(float value, float min, float max)
    {
        return value < min ? min : Math.min(value, max);
    }

    public static double clamp(double value, double min, double max)
    {
        return value < min ? min : Math.min(value, max);
    }

    public static int floor(float value)
    {
        int i = (int) value;
        return value < (float) i ? i - 1 : i;
    }

    public static int floor(double value)
    {
        int i = (int) value;
        return value < (double) i ? i - 1 : i;
    }

    public static float round(float value, int decimalPlaces)
    {
        if (decimalPlaces < 0 || decimalPlaces > 9) { decimalPlaces = 0; }
        float fixedDec = value;
        double scale = Math.pow(10.0, decimalPlaces);
        fixedDec *= (float) scale;
        fixedDec = Math.round(fixedDec);
        return (fixedDec / (float) scale);
    }

    public static double round(double value, int decimalPlaces)
    {
        if (decimalPlaces < 0 || decimalPlaces > 9) { decimalPlaces = 0; }
        int scale = (int) Math.pow(10, decimalPlaces);
        double scaledUp = value * scale;
        double dec = scaledUp % 1d;
        double fixedDec = Math.round(dec * 10) / 10.;
        double newValue = scaledUp + fixedDec;
        return (double) Math.round(newValue) / scale;
    }

    public static int roundUp(int value, int interval)
    {
        if (interval == 0) { return 0; }
        else if (value == 0) { return interval; }
        else
        {
            if (value < 0) { interval *= -1; }
            int remainder = value % interval;
            return remainder == 0 ? value : value + interval - remainder;
        }
    }

    public static long roundUp(long number, long interval)
    {
        if (interval == 0) { return 0; }
        else if (number == 0) { return interval; }
        else
        {
            if (number < 0) { interval *= -1; }
            long i = number % interval;
            return i == 0 ? number : number + interval - i;
        }
    }

    public static int roundDown(int value, int interval)
    {
        if (interval == 0 || value == 0) { return 0; }
        else
        {
            if (value < 0) { interval *= -1; }
            int remainder = value % interval;
            return remainder == 0 ? value : value - remainder;
        }
    }

    public static float sqrtf(double value) { return (float) Math.sqrt(value); }

    public static double wrapRadianAngle(double angle)
    {
        double twoPi = 2 * Math.PI;
        angle %= twoPi;
        if (angle < 0) { angle += twoPi; }
        return angle;
    }

    public static int getMinValue(int[] arr)
    {
        if (arr.length == 0) { throw new IllegalArgumentException("Empty array"); }
        int minValue = arr[0];
        for (int i = 1; i < arr.length; ++i) { if (arr[i] < minValue) minValue = arr[i]; }
        return minValue;
    }

    public static int getMaxValue(int[] arr)
    {
        if (arr.length == 0) { throw new IllegalArgumentException("Empty array"); }
        int maxValue = arr[0];
        for (int i = 1; i < arr.length; ++i) { if (arr[i] > maxValue) maxValue = arr[i]; }
        return maxValue;
    }

    public static long getMinValue(long[] arr)
    {
        if (arr.length == 0) { throw new IllegalArgumentException("Empty array"); }
        long minValue = arr[0];
        for (int i = 1; i < arr.length; ++i) { if (arr[i] < minValue) minValue = arr[i]; }
        return minValue;
    }

    public static long getMaxValue(long[] arr)
    {
        if (arr.length == 0) { throw new IllegalArgumentException("Empty array"); }
        long maxValue = arr[0];
        for (int i = 1; i < arr.length; ++i) { if (arr[i] > maxValue) maxValue = arr[i]; }
        return maxValue;
    }

    public static float positiveModulo(float numerator, float denominator)
    {
        return (numerator % denominator + denominator) % denominator;
    }

    public static double positiveModulo(double numerator, double denominator)
    {
        return (numerator % denominator + denominator) % denominator;
    }

    public static float wrapDegrees(float value)
    {
        value %= 360.0f;
        if (value >= 180.0f) { value -= 360.0f; }
        if (value < -180.0f) { value += 360.0f; }
        return value;
    }

    public static double wrapDegrees(double value)
    {
        value %= 360.0;
        if (value >= 180.0) { value -= 360.0; }
        if (value < -180.0) { value += 360.0; }
        return value;
    }

    public static int wrapDegrees(int angle)
    {
        angle %= 360;
        if (angle >= 180) { angle -= 360; }
        if (angle < -180) { angle += 360; }
        return angle;
    }

    public static Vec3 getRotationVector(float yaw, float pitch)
    {
        double f = Math.cos(-yaw * (Math.PI / 180.0) - Math.PI);
        double g = Math.sin(-yaw * (Math.PI / 180.0) - Math.PI);
        double h = -Math.cos(-pitch * (Math.PI / 180.0));
        double i = Math.sin(-pitch * (Math.PI / 180.0));
        return new Vec3(g * h, i, f * h);
    }

    public static long getCoordinateRandom(int x, int y, int z)
    {
        long l = (long) (x * 3129871L) ^ (long) z * 116129781L ^ (long) y;
        return l * l * 42317861L + l * 11L;
    }

    public static long getPositionRandom(Vec3i pos)
    {
        return getCoordinateRandom(pos.getX(), pos.getY(), pos.getZ());
    }

    public static int smallestEncompassingPowerOfTwo(int value)
    {
        int i = value - 1;
        i |= i >> 1;
        i |= i >> 2;
        i |= i >> 4;
        i |= i >> 8;
        i |= i >> 16;
        return i + 1;
    }

    private static boolean isPowerOfTwo(int value)
    {
        return value != 0 && (value & value - 1) == 0;
    }

    public static int log2DeBruijn(int value)
    {
        value = isPowerOfTwo(value) ? value : smallestEncompassingPowerOfTwo(value);
        return MULTIPLY_DE_BRUIJN_BIT_POSITION[(int) ((long) value * 125613361L >> 27) & 31];
    }

    public static int log2(int value)
    {
        return isPowerOfTwo(value) ? log2DeBruijn(value) : log2DeBruijn(value) - 1;
    }

    public static Vec3 scale(Vec3 vec, double factor)
    {
        return new Vec3(vec.x * factor, vec.y * factor, vec.z * factor);
    }

    public static int min(int val1, int val2) { return Math.min(val1, val2); }
    public static float min(float val1, float val2) { return Math.min(val1, val2); }
    public static double min(double val1, double val2) { return Math.min(val1, val2); }
    public static long min(long val1, long val2) { return Math.min(val1, val2); }

    public static int max(int val1, int val2) { return Math.max(val1, val2); }
    public static float max(float val1, float val2) { return Math.max(val1, val2); }
    public static double max(double val1, double val2) { return Math.max(val1, val2); }
    public static long max(long val1, long val2) { return Math.max(val1, val2); }
}
