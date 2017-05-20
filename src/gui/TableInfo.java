/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gui;

import java.util.ArrayList;
import javax.swing.JPanel;
import javax.swing.JTable;

/**
 *
 * @author dan
 */
public class TableInfo {
    
    private final DebugMessage debugger;     // the debugger for message reporting
    private final TableMouseListener tableListener;
    private final String[] headerNames;      // the array of names for the columns
    private final TblEntry newentry;         // a new entry to add to the list

    public ArrayList<TblEntry> list;         // the array list of data to place in the table
        
    TableInfo (JTable table, String header, DebugMessage dbgHandler) {
        headerNames = header.trim().split("[ ]+");
        list = new ArrayList<>();
        newentry = new TblEntry(headerNames.length);
        debugger = dbgHandler;

        // initialize the JTable
        tableListener = new TableMouseListener(table, this, headerNames, dbgHandler);
    }
        
    // This clears all the table data out as well as the new entry values
    public void resetList () {
        list.clear();
        newentry.clearEntry();
    }
        
    // this resets the new entry data prior to setting the contents
    public void newListEntry () {
        newentry.clearEntry();
    }

    // returns the selected list index entries as a string array
    public String[] getData (int ix) {
        if (ix >= 0 && ix < list.size())
            return list.get(ix).getEntry();
        return null;
    }
        
    // this sets the specified column value in the entry to be added
    public void setListEntry (String colSelect, String value) {
        // find the corresponding index for the header in the table
        for (int ix = 0; ix < headerNames.length; ix++) {
            if (headerNames[ix].equalsIgnoreCase(colSelect)) {
                // save the value in the corresponding slot
                newentry.setEntry(ix, value);
                return;
            }
        }
    }
        
    // adds the new entry to the list
    public void addListEntry () {
        list.add(new TblEntry(newentry));
    }
    
    // initializes the new entry value with the contents of the specified list entry
    public void saveListEntry (int ix) {
        newentry.copyEntry(list.get(ix));
    }
    
    // replaces the specified entry in the list with the data from new entry
    public void replaceListEntry (int ix) {
        if (ix >= 0 && ix < list.size()) {
            list.get(ix).copyEntry(newentry);
        }
    }
        
    // gets the specified value from the table
    public String getListEntry (int index, String colSelect) {
        // find the corresponding index for the header in the table
        for (int ix = 0; ix < headerNames.length; ix++) {
            if (headerNames[ix].equalsIgnoreCase(colSelect)) {
                // get the value from the corresponding slot
                return list.get(index).getEntry(ix);
            }
        }
        return null;
    }

    /**
     * determines if the specified new entry column value was found in the list.
     * colSelect can be one or more entries, and all specified must match.
     * colSelect cal be null to match all column entries.
     * 
     * @param colSelect - the column name(s) to match the newentry to list elements
     * 
     * @return - list index of entry that matches, -1 if no match
     */
    public int findListEntry (String colSelect) {
        if (!list.isEmpty()) {
            // find the column selections to match
            ArrayList<Integer> collist = new ArrayList<>();
            for (int ix = 0; ix < headerNames.length; ix++) {
                if (colSelect == null || headerNames[ix].equalsIgnoreCase(colSelect)) {
                    collist.add(ix);
                }
            }
            if (collist.isEmpty())
                return -1;

            for (int rowix = 0; rowix < list.size(); rowix++) {
                boolean bMatch = true;
                for (int colsel = 0; colsel < collist.size(); colsel++) {
                    int colix = collist.get(colsel);
                    String tbldata = list.get(rowix).getEntry(colix);
                    String newdata = newentry.getEntry(colix);
                    if (!tbldata.equals(newdata)) {
                        bMatch = false;
                        break;
                    }
                }
                if (bMatch)
                    return rowix;
            }
        }
        return -1;
    }
        
    // indicates all entries need updating
    public void initListUpdates () {
        for (TblEntry entry : list) {
            entry.setUpdate(false);
        }
    }
    
    // returns true if specified list entry was updated
    public boolean isListUpdated (int ix) {
        return list.get(ix).getUpdate();
    }

    // updates the GUI table display
    public void tableUpdate () {
        tableListener.tableSortAndDisplay (this);
    }
}
