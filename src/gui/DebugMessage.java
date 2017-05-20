/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gui;

import java.awt.Graphics;
import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;

/**
 *
 * @author dan
 */
public class DebugMessage {
    
    public enum StatusType {
        Info, Event, JobStarted, JobCompleted, Background, Warning, Error, Results;
    }

    private static final String newLine = System.getProperty("line.separator");

    private final JTextPane    debugTextPane;
    private final ElapsedTimer elapsedTimer;

    DebugMessage (JTextPane textPane, ElapsedTimer elapsed) {
        debugTextPane = textPane;
        elapsedTimer  = elapsed;
    }
    
    /**
     * A generic function for appending formatted text to a JTextPane.
     * 
     * @param tp    - the TextPane to append to
     * @param msg   - message contents to write
     * @param color - color of text
     * @param font  - the font selection
     * @param size  - the font point size
     * @param ftype - type of font style
     */
    private static void appendToPane(JTextPane tp, String msg, Util.TextColor color, String font, int size, Util.FontType ftype)
    {
        AttributeSet aset = Util.setTextAttr(color, font, size, ftype);
        int len = tp.getDocument().getLength();
        tp.setCaretPosition(len);
        tp.setCharacterAttributes(aset, false);
        tp.replaceSelection(msg);
    }

    /**
     * A generic function for appending formatted text to a JTextPane.
     * 
     * @param tp    - the TextPane to append to
     * @param msg   - message contents to write
     * @param color - color of text
     * @param ftype - type of font style
     */
    private static void appendToPane(JTextPane tp, String msg, Util.TextColor color, Util.FontType ftype)
    {
        appendToPane(tp, msg, color, "Courier", 11, ftype);
    }

    /**
     * outputs the various types of messages to the status display.
     * all messages will guarantee the previous line was terminated with a newline,
     * and will preceed the message with a timestamp value and terminate with a newline.
     * 
     * @param type    - the type of message
     * @param message - the message to display
     */
    public void print(StatusType type, String message) {
        // skip if no message
        if (message == null || message.isEmpty())
            return;
        
        // if we were in the middle of processing a jar, terminate the record
        String tstamp = "";
        if (type != StatusType.Results) {
            tstamp += "[" + elapsedTimer.getElapsed() + "] ";
            appendToPane(debugTextPane, tstamp,
                        Util.TextColor.Brown, Util.FontType.Bold);
        }

        switch (type) {
            // the following preceed the message with a timestamp and terminate with a newline
            default:    // fall through...
            case Info:
                appendToPane(debugTextPane, message + newLine,
                            Util.TextColor.Black, Util.FontType.Normal);
                break;

            case Event:
                appendToPane(debugTextPane, message + newLine,
                            Util.TextColor.Gold, Util.FontType.Normal);
                break;

            case JobStarted:
                appendToPane(debugTextPane, message + newLine,
                            Util.TextColor.Green, Util.FontType.Normal);
                break;

            case JobCompleted:
                appendToPane(debugTextPane, message + newLine,
                            Util.TextColor.Blue, Util.FontType.Normal);
                break;

            case Background:
                appendToPane(debugTextPane, message + newLine,
                            Util.TextColor.Brown, Util.FontType.Normal);
                break;

            case Error:
                appendToPane(debugTextPane, "ERROR: " + message + newLine,
                            Util.TextColor.Red, Util.FontType.Bold);
                break;

            case Warning:
                appendToPane(debugTextPane, "WARNING: " + message + newLine,
                            Util.TextColor.LtRed, Util.FontType.Bold);
                break;

            case Results:
                String line;
                int offset = message.indexOf(newLine);
                while (offset >= 0) {
                    line = message.substring(0, offset);
                    message = message.substring(offset+1);
                    offset = message.indexOf(newLine);

                    appendToPane(debugTextPane, "        " + line + newLine,
                                Util.TextColor.Violet, Util.FontType.Bold);
                }
                break;
        }

        // force an update
        Graphics graphics = debugTextPane.getGraphics();
        if (graphics != null)
            debugTextPane.update(graphics);
    }
}
