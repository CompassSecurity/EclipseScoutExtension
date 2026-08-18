package ch.csnc.burp;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.TransferHandler;



public class EclipseScoutTableRowTransferHandler extends TransferHandler {

    private final JTable table;
    private final EclipseScoutTableModel model;
    private int draggedModelRow;

    public EclipseScoutTableRowTransferHandler(JTable table, EclipseScoutTableModel model) {
        this.table = table;
        this.model = model;
    }

    @Override
    protected Transferable createTransferable(JComponent c) {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        draggedModelRow = table.convertRowIndexToModel(viewRow);
        return new StringSelection(String.valueOf(draggedModelRow));
    }

    @Override
    public int getSourceActions(JComponent c) {
        return MOVE;
    }

    @Override
    public boolean canImport(TransferSupport support) {
        return support.isDrop() && support.isDataFlavorSupported(DataFlavor.stringFlavor);
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) {
            return false;
        }
        JTable.DropLocation dl = (JTable.DropLocation) support.getDropLocation();
        int viewTargetRow = dl.getRow();
        int modelTargetRow = table.convertRowIndexToModel(viewTargetRow);
        // Account for removing the original row first
        if (draggedModelRow < modelTargetRow) {
            modelTargetRow--;
        }
        modelTargetRow = Math.max(0, Math.min(modelTargetRow, model.getRowCount() - 1));
        model.moveRow(draggedModelRow, modelTargetRow);
        return true;
    }
}
