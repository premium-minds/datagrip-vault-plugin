package com.premiumminds.datagrip.vault;

import javax.swing.JComponent;
import javax.swing.JPanel;

import com.intellij.database.dataSource.DatabaseAuthProvider;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.dataSource.url.template.MutableParametersHolder;
import com.intellij.database.dataSource.url.template.ParametersHolder;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.uiDesigner.core.GridLayoutManager;
import org.jetbrains.annotations.NotNull;

import static com.intellij.database.dataSource.url.ui.UrlPropertiesPanel.createLabelConstraints;
import static com.intellij.database.dataSource.url.ui.UrlPropertiesPanel.createSimpleConstraints;
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
    public void save(@NotNull LocalDataSource localDataSource, boolean b) {
        localDataSource.getAdditionalJdbcProperties().put(PROP_SECRET, secretText.getText());
        localDataSource.getAdditionalJdbcProperties().put(PROP_ADDRESS, addressText.getText());
        localDataSource.getAdditionalJdbcProperties().put(PROP_TOKEN_FILE, tokenFileText.getText());
    }

    @Override
    public void reset(@NotNull LocalDataSource localDataSource, boolean b) {
        secretText.setText(localDataSource.getAdditionalJdbcProperties().get(PROP_SECRET));
        addressText.setText(localDataSource.getAdditionalJdbcProperties().get(PROP_ADDRESS));
        tokenFileText.setText(localDataSource.getAdditionalJdbcProperties().get(PROP_TOKEN_FILE));
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
}
