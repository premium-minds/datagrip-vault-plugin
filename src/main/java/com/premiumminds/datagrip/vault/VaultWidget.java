package com.premiumminds.datagrip.vault;

import java.awt.Dimension;
import javax.swing.JComponent;
import javax.swing.JPanel;

import com.intellij.database.dataSource.DatabaseAuthProvider;
import com.intellij.database.dataSource.DatabaseConnectionConfig;
import com.intellij.database.dataSource.DatabaseConnectionPoint;
import com.intellij.database.dataSource.url.template.MutableParametersHolder;
import com.intellij.database.dataSource.url.template.ParametersHolder;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import org.jetbrains.annotations.NotNull;

import static com.premiumminds.datagrip.vault.VaultDatabaseAuthProvider.PROP_ADDRESS;
import static com.premiumminds.datagrip.vault.VaultDatabaseAuthProvider.PROP_SECRET;
import static com.premiumminds.datagrip.vault.VaultDatabaseAuthProvider.PROP_TOKEN_FILE;

public class VaultWidget implements DatabaseAuthProvider.AuthWidget {

    private JPanel panel;
    private JBTextField addressText;
    private JBTextField secretText;
    private JBTextField tokenFileText;

    public VaultWidget() {

        final var vaultBundle = new VaultBundle();

        addressText = new JBTextField();
        secretText = new JBTextField();
        tokenFileText = new JBTextField();

        addressText.getEmptyText().setText("e.g.: http://example.com");
        secretText.getEmptyText().setText("e.g.: secret/my-secret");
        tokenFileText.getEmptyText().setText("Default: $HOME/.vault-token");

        panel = new JPanel(new GridLayoutManager(3, 6));

        final var secretLabel = new JBLabel(vaultBundle.getMessage("secret"));
        final var addressLabel = new JBLabel(vaultBundle.getMessage("address"));
        final var tokenFileLabel = new JBLabel(vaultBundle.getMessage("tokenFile"));

        panel.add(addressLabel, createLabelConstraints(0, 0, addressLabel.getPreferredSize().getWidth()));
        panel.add(addressText, createSimpleConstraints(0, 1, 3));

        panel.add(secretLabel, createLabelConstraints(1, 0, secretLabel.getPreferredSize().getWidth()));
        panel.add(secretText, createSimpleConstraints(1, 1, 3));

        panel.add(tokenFileLabel, createLabelConstraints(2, 0, tokenFileLabel.getPreferredSize().getWidth()));
        panel.add(tokenFileText, createSimpleConstraints(2, 1, 3));
    }

    @Override
    public void save(@NotNull final DatabaseConnectionConfig config, final boolean copyCredentials) {
        config.setAdditionalProperty(PROP_SECRET, secretText.getText());
        config.setAdditionalProperty(PROP_ADDRESS, addressText.getText());
        config.setAdditionalProperty(PROP_TOKEN_FILE, tokenFileText.getText());
    }

    @Override
    public void reset(@NotNull final DatabaseConnectionPoint point, final boolean resetCredentials) {
        secretText.setText(point.getAdditionalProperty(PROP_SECRET));
        addressText.setText(point.getAdditionalProperty(PROP_ADDRESS));
        tokenFileText.setText(point.getAdditionalProperty(PROP_TOKEN_FILE));
    }

    @Override
    public void onChanged(@NotNull final Runnable runnable) {

    }

    @Override
    public boolean isPasswordChanged() {
        return false;
    }

    @Override
    public void hidePassword() {

    }

    @Override
    public void reloadCredentials() {

    }

    @Override
    public @NotNull JComponent getComponent() {
        return panel;
    }

    @Override
    public @NotNull JComponent getPreferredFocusedComponent() {
        return addressText;
    }

    @Override
    public void forceSave() {

    }

    @Override
    public void updateFromUrl(@NotNull ParametersHolder parametersHolder) {

    }

    @Override
    public void updateUrl(@NotNull MutableParametersHolder mutableParametersHolder) {

    }

    public static GridConstraints createLabelConstraints(int row, int col, double width) {
        return createConstraints(row, col, 1, 0, 3, (int)width, false);
    }

    public static GridConstraints createSimpleConstraints(int row, int col, int colSpan) {
        return createConstraints(row, col, colSpan, 0, 1, -1, true);
    }

    public static GridConstraints createConstraints(int row, int col, int colSpan, int anchor, int fill, int prefWidth, boolean rubber) {
        return createConstraints(row, col, 1, colSpan, anchor, fill, prefWidth, rubber);
    }

    public static GridConstraints createConstraints(int row, int col, int rowSpan, int colSpan, int anchor, int fill, int prefWidth, boolean rubber) {
        return createConstraints(row, col, rowSpan, colSpan, anchor, fill, prefWidth, rubber, false);
    }

    public static GridConstraints createConstraints(int row, int col, int rowSpan, int colSpan, int anchor, int fill, int prefWidth, boolean rubber, boolean vrubber) {
        Dimension nonPref = new Dimension(-1, -1);
        Dimension pref = new Dimension(prefWidth == -1 ? 100 : prefWidth, -1);
        return new GridConstraints(row, col, rowSpan, colSpan, anchor, fill, getPolicy(rubber), getPolicy(vrubber), nonPref, pref, nonPref, 0, true);
    }

    public static int getPolicy(boolean rubber) {
        return rubber ? 7 : 0;
    }
}
