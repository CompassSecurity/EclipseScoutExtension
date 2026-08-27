package ch.csnc.burp;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.TransferHandler;

/**
 * Enables drag-and-drop row reordering on the sequence table: dragging a row records its model
 * index, and dropping it elsewhere moves that row to the target index in the underlying model.
 */
public class EclipseScoutTableRowTransferHandler extends TransferHandler {

    private final JTable table;
    private final EclipseScoutTableModel model;
    private int draggedModelRow;

    /** Wires drag-and-drop row reordering onto {@code table}, backed by {@code model}. */
    public EclipseScoutTableRowTransferHandler(JTable table, EclipseScoutTableModel model) {
        this.table = table;
        this.model = model;
    }

    /** Records the dragged row's model index so {@link #importData} knows which row to move. */
    @Override
    protected Transferable createTransferable(JComponent c) {
        var viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        draggedModelRow = table.convertRowIndexToModel(viewRow);
        // The dragged row's model index is the only thing importData() needs to complete the
        // move; the transferable's payload itself is otherwise unused.
        return new StringSelection(String.valueOf(draggedModelRow));
    }

    /** Restricts this handler to row-moving drags; copy is not supported. */
    @Override
    public int getSourceActions(JComponent c) {
        return MOVE;
    }

    /** Accepts only drops carrying the string payload produced by {@link #createTransferable}. */
    @Override
    public boolean canImport(TransferSupport support) {
        return support.isDrop() && support.isDataFlavorSupported(DataFlavor.stringFlavor);
    }

    /**
     * Moves the row recorded by {@link #createTransferable} to the drop target's row, adjusting
     * for the shift caused by removing the row from its original position first.
     */
    @Override
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) {
            return false;
        }
        var dl = (JTable.DropLocation) support.getDropLocation();
        var viewTargetRow = dl.getRow();
        var modelTargetRow = table.convertRowIndexToModel(viewTargetRow);
        // Account for removing the original row first
        if (draggedModelRow < modelTargetRow) {
            modelTargetRow--;
        }
        modelTargetRow = Math.max(0, Math.min(modelTargetRow, model.getRowCount() - 1));
        model.moveRow(draggedModelRow, modelTargetRow);
        return true;
    }
}
