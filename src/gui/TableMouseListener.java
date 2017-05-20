/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gui;

import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.Comparator;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;

/**
 *
 * @author dan
 */
public class TableMouseListener extends MouseAdapter {

    private final TableInfo tableInfo;
    private int      iSortSelection;         // specifies the column on which to sort
    private boolean  bSortOrder;             // specifies either ascending (false) or descending (true) order.
    private final String[] headerNames;      // the array of names for the columns
    private final JTable   guitable;
    private final DebugMessage debugger;     // the debugger for message reporting
        
    TableMouseListener (JTable table, TableInfo tblInfo, String[] header, DebugMessage dbgHandler) {
        tableInfo = tblInfo;
        guitable = table;
        debugger = dbgHandler;
        
        headerNames = header.clone();
        iSortSelection = 0;
        bSortOrder = false;

        JTableHeader processTableHeader = guitable.getTableHeader();
        processTableHeader.addMouseListener(this);
        tableCenter();
    }
        
    @Override
    public void mouseClicked (MouseEvent evt) {
        String itemName = "table header mouseClicked";

        if (tableInfo == null)
            return;
            
        // get the selected header column
        int newSelection = guitable.columnAtPoint(evt.getPoint());
        if (newSelection >= 0) {
            int oldSelection = iSortSelection;
            iSortSelection = newSelection;
            String colname = guitable.getColumnName(newSelection);
            debugger.print(DebugMessage.StatusType.Event, itemName + ": (" + evt.getX() + "," + evt.getY() + ") -> col " + newSelection + " = " + colname);

            // invert the order selection if the same column is specified
            if (oldSelection == newSelection)
                bSortOrder = !bSortOrder;
                    
            // sort the table entries based on current selections
            tableSortAndDisplay(tableInfo);
        }
        else {
            debugger.print(DebugMessage.StatusType.Event, itemName + ": (" + evt.getX() + "," + evt.getY() + ") -> col " + newSelection);
        }
    }

    /**
     * aligns the table columns to center.
     */
    private void tableCenter () {
        // align columns in cloud jobs table to center
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment( SwingConstants.CENTER );
        guitable.setDefaultRenderer(String.class, centerRenderer);
        TableCellRenderer headerRenderer = guitable.getTableHeader().getDefaultRenderer();
        JLabel headerLabel = (JLabel) headerRenderer;
        headerLabel.setHorizontalAlignment( SwingConstants.CENTER );
    }
    
    /**
     * This is used to resize the colums of a table to accomodate some columns
     * requiring more width than others.
     */
    private void tableResizeColumnWidth () {
        final TableColumnModel columnModel = guitable.getColumnModel();
        for (int column = 0; column < guitable.getColumnCount(); column++) {
            int width = 15; // Min width
            for (int row = 0; row < guitable.getRowCount(); row++) {
                TableCellRenderer renderer = guitable.getCellRenderer(row, column);
                Component comp = guitable.prepareRenderer(renderer, row, column);
                width = Math.max(comp.getPreferredSize().width +1 , width);
            }
            if(width > 300) // Max width
                width=300;
            columnModel.getColumn(column).setPreferredWidth(width);
        }
    }

    /**
     * This performs a sort on the tableList and updates the table display.
     * 
     * @param tableInfo  - the class that contains the table to sort and display
     */
    public void tableSortAndDisplay (TableInfo tableInfo) {
        // sort the table entries
        Collections.sort(tableInfo.list, new Comparator<TblEntry>() {
            @Override
            public int compare(TblEntry job1, TblEntry job2)
            {
                String object1 = job1.getEntry(iSortSelection);
                String object2 = job2.getEntry(iSortSelection);
                if (!bSortOrder)
                    return  object1.compareTo(object2);
                else
                    return  object2.compareTo(object1);
            }
        });

        // clear out the table entries
        DefaultTableModel model = (DefaultTableModel) guitable.getModel();
        model.setRowCount(0); // this clears all the entries from the table

        // modify header name to include sort direction of selected column
        String[] colNames = headerNames.clone();
        for (int ix = 0; ix < model.getColumnCount(); ix++) {
            if (ix == iSortSelection) {
                if (bSortOrder)
                    colNames[ix] = colNames[ix] + " " + "\u2193".toCharArray()[0]; // DOWN arrow
                else
                    colNames[ix] = colNames[ix] + " " + "\u2191".toCharArray()[0]; // UP arrow
            }
        }
        if (colNames != null)
            model.setColumnIdentifiers(colNames);
        
        // now copy the entries to the displayed table
        for (int ix = 0; ix < tableInfo.list.size(); ix++) {
            model.addRow(tableInfo.getData(ix));
        }

        // auto-resize column width
        tableResizeColumnWidth();
    }
    
}
