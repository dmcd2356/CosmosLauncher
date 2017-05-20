package gui;

import java.awt.Color;
import javax.swing.text.AttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;


/**
 * @author dmcd2356
 *
 */
public class Util {
    public enum FontType {
        Normal, Bold, Italic, BoldItalic;
    }
    
    public enum TextColor {
        Black, DkGrey, DkRed, Red, LtRed, Orange, Brown, Gold, Green, Cyan, LtBlue, Blue, Violet, DkVio;
    }
    
    /**
     * generates the specified text color for the debug display.
     * 
     * @param colorName - name of the color to generate
     * @return corresponding Color value representation
     */
    public static Color generateColor (TextColor colorName) {
        float hue, sat, bright;
        switch (colorName) {
            default:
            case Black:  return Color.BLACK;
            case DkGrey: return Color.DARK_GRAY;
            case DkRed:  hue = (float)0;   sat = (float)100; bright = (float)66;  break;
            case Red:    hue = (float)0;   sat = (float)100; bright = (float)90;  break;
            case LtRed:  hue = (float)0;   sat = (float)60;  bright = (float)100; break;
            case Orange: hue = (float)20;  sat = (float)100; bright = (float)100; break;
            case Brown:  hue = (float)20;  sat = (float)80;  bright = (float)66;  break;
            case Gold:   hue = (float)40;  sat = (float)100; bright = (float)90;  break;
            case Green:  hue = (float)128; sat = (float)100; bright = (float)45;  break;
            case Cyan:   hue = (float)190; sat = (float)80;  bright = (float)45;  break;
            case LtBlue: hue = (float)210; sat = (float)100; bright = (float)90;  break;
            case Blue:   hue = (float)240; sat = (float)100; bright = (float)100; break;
            case Violet: hue = (float)267; sat = (float)100; bright = (float)100; break;
            case DkVio:  hue = (float)267; sat = (float)100; bright = (float)66;  break;
        }
        hue /= (float)360.0;
        sat /= (float)100.0;
        bright /= (float) 100.0;
        return Color.getHSBColor(hue, sat, bright);
    }

    /**
     * A generic function for appending formatted text to a JTextPane.
     * 
     * @param color - color of text
     * @param font  - the font selection
     * @param size  - the font point size
     * @param ftype - type of font style
     * @return the attribute set
     */
    public static AttributeSet setTextAttr(TextColor color, String font, int size, FontType ftype)
    {
        boolean bItalic = false;
        boolean bBold = false;
        if (ftype == FontType.Italic || ftype == FontType.BoldItalic)
            bItalic = true;
        if (ftype == FontType.Bold || ftype == FontType.BoldItalic)
            bBold = true;

        StyleContext sc = StyleContext.getDefaultStyleContext();
        AttributeSet aset = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, generateColor(color));

        aset = sc.addAttribute(aset, StyleConstants.FontFamily, font);
        aset = sc.addAttribute(aset, StyleConstants.FontSize, size);
        aset = sc.addAttribute(aset, StyleConstants.Alignment, StyleConstants.ALIGN_JUSTIFIED);
        aset = sc.addAttribute(aset, StyleConstants.Italic, bItalic);
        aset = sc.addAttribute(aset, StyleConstants.Bold, bBold);
        return aset;
    }
    
}

