/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gui;

import java.util.Arrays;

/**
 *
 * @author dan
 */
public class TblEntry {
    private String[] tbldata;
    private boolean  bUpdated;
        
    // creates a new empty entry
    public TblEntry (int size) {
        // create the initial array of the specified size
        tbldata = new String[size];
        Arrays.fill (tbldata, "");
        bUpdated = false;
    }
        
    // creates a new entry that is a copy of another entry
    public TblEntry (TblEntry info) {
        // create the initial array of the specified size
        tbldata = new String[info.tbldata.length];
        System.arraycopy(info.tbldata, 0, tbldata, 0, tbldata.length);
        bUpdated = info.bUpdated;
    }
        
    // resets the entry to all blank fields and indicates not updated
    public void clearEntry () {
        // reset the entries to blanks
        Arrays.fill (tbldata, "");
        bUpdated = false;
    }
        
    // copies the data content and marks the entry as updated
    public void copyEntry (TblEntry newdata) {
        System.arraycopy(newdata.tbldata, 0, tbldata, 0, tbldata.length);
        bUpdated = true;
    }
        
    // sets only the specified item in the entry and marks the entry as updated
    public void setEntry (int ix, String value) {
        if (ix >= 0 && ix < tbldata.length) {
            tbldata[ix] = value;
            bUpdated = true;
        }
    }

    // sets the updated indicator
    public void setUpdate (boolean bUpdate) {
        bUpdated = bUpdate;
    }
    
    // returns the updated indicator
    public boolean getUpdate () {
        return bUpdated;
    }
    
    // return value of the specified item in the entry
    public String getEntry (int ix) {
        if (ix >= 0 && ix < tbldata.length) {
            return tbldata[ix];
        }
        return null;
    }

    // return the entire entry
    public String[] getEntry () {
        return tbldata;
    }
}
    
