
package master.flame.danmaku.danmaku.model;

public class Duration {

    private long mInitialDuration;

    private float factor = 1.0f;

    public long value;

    public Duration(long initialDuration) {
        mInitialDuration = initialDuration;
        value = initialDuration;
    }
    
    public void setValue(long initialDuration) {
        mInitialDuration = initialDuration;
        value = (long) (mInitialDuration / normalizeFactor(factor));
    }

    public void setFactor(float f) {
        if (factor != f) {
            factor = f;
            value = (long) (mInitialDuration / normalizeFactor(f));
        }
    }

    private float normalizeFactor(float f) {
        return f <= 0f ? 1.0f : f;
    }

}
