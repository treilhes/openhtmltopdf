package com.openhtmltopdf.css.style.derived;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.openhtmltopdf.css.constants.CSSName;
import com.openhtmltopdf.css.constants.Idents;
import com.openhtmltopdf.css.parser.CSSPrimitiveValue;
import com.openhtmltopdf.css.parser.FSColor;
import com.openhtmltopdf.css.parser.FSFunction;
import com.openhtmltopdf.css.parser.PropertyValue;
import com.openhtmltopdf.css.parser.property.AbstractPropertyBuilder;
import com.openhtmltopdf.css.parser.property.Conversions;
import com.openhtmltopdf.css.style.CalculatedStyle;
import com.openhtmltopdf.css.style.CssContext;

public class FSLinearGradient {

    /**
     * A stop point which does not yet have a length.
     * We need all the stop points first before we can calculate
     * a length for intermediate stop points without a length.
     */
    private static class IntermediateStopPoint {
        private final FSColor _color;

        IntermediateStopPoint(FSColor color) {
            _color = color;
        }

        public FSColor getColor() {
            return _color;
        }
    }

    public static class StopPoint extends IntermediateStopPoint {
        private final float _length;

        public StopPoint(FSColor color, float length) {
            super(color);
            this._length = length;
        }

        public float getLength() {
            return _length;
        }
    }

    private final List<StopPoint> _stopPoints;
    private final float _angle;
    // TODO.
    // private final int x1;
    // private final int x2;
    // private final int y1;
    // private final int y2;

    public FSLinearGradient(CalculatedStyle style, FSFunction function, float boxWidth, CssContext ctx) {
        List<PropertyValue> params = function.getParameters();
        int stopsStartIndex = getStopsStartIndex(params);

        float prelimAngle = calculateAngle(params, stopsStartIndex);
        prelimAngle = prelimAngle % 360f;
        if (prelimAngle < 0) {
            prelimAngle += 360f;
        }

        this._angle = prelimAngle;
        this._stopPoints = calculateStopPoints(params, style, ctx, boxWidth, stopsStartIndex);
    }

    private boolean isLengthOrPercentage(PropertyValue value) {
        return AbstractPropertyBuilder.isLengthHelper(value) || 
               value.getPrimitiveType() == CSSPrimitiveValue.CSS_PERCENTAGE;
    }

    private List<StopPoint> calculateStopPoints(
        List<PropertyValue> params, CalculatedStyle style, CssContext ctx, float boxWidth, int stopsStartIndex) {

        List<IntermediateStopPoint> points = new ArrayList<>();

        for (int i = stopsStartIndex; i < params.size(); i++) {
            PropertyValue value = params.get(i);
            FSColor color;

            if (value.getPrimitiveType() == CSSPrimitiveValue.CSS_IDENT) {
                color = Conversions.getColor(value.getStringValue());
            } else {
                color = value.getFSColor();
            }

            if (i + 1 < params.size() && isLengthOrPercentage(params.get(i + 1))) {

                PropertyValue lengthValue = params.get(i + 1);
                float length = LengthValue.calcFloatProportionalValue(style, CSSName.BACKGROUND_IMAGE, "",
                        lengthValue.getFloatValue(), lengthValue.getPrimitiveType(), boxWidth, ctx);
                points.add(new StopPoint(color, length));
            } else {
                points.add(new IntermediateStopPoint(color));
            }
        }

        List<StopPoint> ret = new ArrayList<>(points.size());

        for (int i = 0; i < points.size(); i++) {
            IntermediateStopPoint pt = points.get(i);
            boolean intermediate = pt.getClass() == IntermediateStopPoint.class;
            
            if (!intermediate) {
                ret.add((StopPoint) pt);
            } else if (i == 0) {
                ret.add(new StopPoint(pt.getColor(), 0f));
            } else if (i == points.size() - 1) {
                float len = get100PercentDefaultStopLength(style, ctx, boxWidth);
                ret.add(new StopPoint(pt.getColor(), len));
            } else {
                // Poo, we've got a length-less stop in the middle.
                // Lets say we have linear-gradient(to right, red, blue 10px, orange, yellow, black 100px, purple):
                // In this case because orange and yellow don't have lengths we have to devide the difference
                // between them. So difference = 90px and there are 3 color changes means that the interval
                // will be 30px and that orange will be at 40px and yellow at 70px.
                int nextWithLengthIndex = getNextStopPointWithLengthIndex(points, i + 1);
                int prevWithLengthIndex = getPrevStopPointWithLengthIndex(points, i - 1);

                float nextLength = nextWithLengthIndex == -1 ?
                                    get100PercentDefaultStopLength(style, ctx, boxWidth) :
                                    ((StopPoint) points.get(nextWithLengthIndex)).getLength();

                float prevLength = prevWithLengthIndex == -1 ? 0 :
                                    ((StopPoint) points.get(prevWithLengthIndex)).getLength();

                float range = nextLength - prevLength;

                int topRangeIndex = nextWithLengthIndex == -1 ? points.size() - 1 : nextWithLengthIndex;
                int bottomRangeIndex = prevWithLengthIndex == -1 ? 0 : prevWithLengthIndex;
                
                int rangeCount = (topRangeIndex - bottomRangeIndex) + 1;
                int thisCount = i - bottomRangeIndex;

                // TODO: Check for div by zero.
                float interval = range / rangeCount;

                float thisLength = prevLength + (interval * thisCount);

                ret.add(new StopPoint(pt.getColor(), thisLength));
            }
        }

        return ret;
    }

    private int getPrevStopPointWithLengthIndex(List<IntermediateStopPoint> points, int maxIndex) {
        for (int i = maxIndex; i >= 0; i--) {
            if (isStopPointWithLength(points.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private float get100PercentDefaultStopLength(CalculatedStyle style, CssContext ctx, float boxWidth) {
        return LengthValue.calcFloatProportionalValue(style, CSSName.BACKGROUND_IMAGE, "100%",
                100f, CSSPrimitiveValue.CSS_PERCENTAGE, boxWidth, ctx);
    }

    private boolean isStopPointWithLength(IntermediateStopPoint pt) {
        return pt.getClass() == IntermediateStopPoint.class;
    }

    private int getNextStopPointWithLengthIndex(List<IntermediateStopPoint> points, int startIndex) {
        for (int i = startIndex; i < points.size(); i++) {
            if (isStopPointWithLength(points.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private int getStopsStartIndex(List<PropertyValue> params) {
        if (Objects.equals(params.get(0).getStringValue(), "to")) {
            int i = 1;
            while (i < params.size() && 
                   Idents.looksLikeABGPosition(params.get(i).getStringValue())) {
                 i++;
            }

            return i;
        } else {
            return 1;
        }
    }

    /**
     * Calculates the angle of the linear gradient in degrees.
     */
    private float calculateAngle(List<PropertyValue> params, int stopsStartIndex) {
        if (Objects.equals(params.get(0).getStringValue(), "to")) {
            // The to keyword is followed by one or two position
            // idents (in any order).
            // linear-gradient( to left top, blue, red);
            // linear-gradient( to top right, blue, red);
            List<String> positions = new ArrayList<>(2);

            for (int i = 1; i < stopsStartIndex; i++) {
                 positions.add(params.get(i).getStringValue());
            }

            if (positions.contains("top") && positions.contains("left"))
                return 315f;
            else if (positions.contains("top") && positions.contains("right"))
                return 45f;
            else if (positions.contains("bottom") && positions.contains("left"))
                return 225f;
            else if (positions.contains("bottom") && positions.contains("right"))
                return 135f;
            else if (positions.contains("bottom"))
                return 180f;
            else
                return 0f;
        }
        else if (params.get(0).getPrimitiveType() == CSSPrimitiveValue.CSS_DEG)
        {
            // linear-gradient(45deg, ...)
            return params.get(0).getFloatValue();
        }
        else if (params.get(0).getPrimitiveType() == CSSPrimitiveValue.CSS_RAD)
        {
            // linear-gradient(2rad)
            return params.get(0).getFloatValue() * (float) (180 / Math.PI);
        }
        else
        {
            return 0f;
        }
    }

    public List<StopPoint> getStopPoints() {
        return _stopPoints;
    }

    /**
     * The angle of this linear gradient in compass degrees.
     */
    public float getAngle() {
        return _angle;
    }
}
