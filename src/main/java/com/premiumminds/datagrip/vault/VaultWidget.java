package com.premiumminds.datagrip.vault;

import java.awt.Component;
import java.awt.Dimension;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;

import com.intellij.database.dataSource.DatabaseAuthProvider;
import com.intellij.database.dataSource.DatabaseConnectionConfig;
import com.intellij.database.dataSource.DatabaseConnectionPoint;
import com.intellij.database.dataSource.url.template.MutableParametersHolder;
import com.intellij.database.dataSource.url.template.ParametersHolder;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import org.jetbrains.annotations.NotNull;

import static com.premiumminds.datagrip.vault.VaultDatabaseAuthProvider.PROP_ADDRESS;
import static com.premiumminds.datagrip.vault.VaultDatabaseAuthProvider.PROP_CERTIFICATE;
import static com.premiumminds.datagrip.vault.VaultDatabaseAuthProvider.PROP_PASSWORD_KEY;
import static com.premiumminds.datagrip.vault.VaultDatabaseAuthProvider.PROP_SECRET;
import static com.premiumminds.datagrip.vault.VaultDatabaseAuthProvider.PROP_SECRET_TYPE;
import static com.premiumminds.datagrip.vault.VaultDatabaseAuthProvider.PROP_TOKEN_FILE;
import static com.premiumminds.datagrip.vault.VaultDatabaseAuthProvider.PROP_USERNAME_KEY;

public class VaultWidget implements DatabaseAuthProvider.AuthWidget {

    private final JPanel panel;
    private final JBTextField addressText;
    private final JBTextField secretText;
    private final JBTextField tokenFileText;
    private final JBTextField certificateText;
    private final ComboBox<SecretType> secretType;
    private final JBTextField usernameKeyText;
    private final JBTextField passwordKeyText;

    public VaultWidget() {

        final var vaultBundle = new VaultBundle();

        addressText = new JBTextField();
        secretText = new JBTextField();
        tokenFileText = new JBTextField();
        certificateText = new JBTextField();
        secretType = new ComboBox<>(SecretType.values());
        usernameKeyText = new JBTextField();
        passwordKeyText = new JBTextField();

        addressText.getEmptyText().setText("e.g.: http://example.com");
        secretText.getEmptyText().setText("e.g.: secret/my-secret");
        tokenFileText.getEmptyText().setText("Default: $HOME/.vault-token");
        certificateText.getEmptyText().setText("Path to certificate");
        usernameKeyText.getEmptyText().setText("username");
        passwordKeyText.getEmptyText().setText("password");

        panel = new JPanel(new GridLayoutManager(7, 6));

        final var addressLabel = new JBLabel(vaultBundle.getMessage("address"));
        final var secretLabel = new JBLabel(vaultBundle.getMessage("secret"));
        final var tokenFileLabel = new JBLabel(vaultBundle.getMessage("tokenFile"));
        final var certificateLabel = new JBLabel(vaultBundle.getMessage("certificate"));
        final var secretTypeLabel = new JBLabel(vaultBundle.getMessage("secretType"));
        final var usernameKeyLabel = new JBLabel(vaultBundle.getMessage("usernameKey"));
        final var passwordKeyLabel = new JBLabel(vaultBundle.getMessage("passwordKey"));

        panel.add(addressLabel, createLabelConstraints(0, 0, addressLabel.getPreferredSize().getWidth()));
        panel.add(addressText, createSimpleConstraints(0, 1, 3));

        panel.add(secretLabel, createLabelConstraints(1, 0, secretLabel.getPreferredSize().getWidth()));
        panel.add(secretText, createSimpleConstraints(1, 1, 3));

        panel.add(tokenFileLabel, createLabelConstraints(2, 0, tokenFileLabel.getPreferredSize().getWidth()));
        panel.add(tokenFileText, createSimpleConstraints(2, 1, 3));

        panel.add(certificateLabel, createLabelConstraints(3, 0, certificateLabel.getPreferredSize().getWidth()));
        panel.add(certificateText, createSimpleConstraints(3, 1, 3));

        panel.add(secretTypeLabel, createLabelConstraints(4, 0, secretTypeLabel.getPreferredSize().getWidth()));
        panel.add(secretType, createSimpleConstraints(4, 1, 3));

        panel.add(usernameKeyLabel, createLabelConstraints(5, 0, usernameKeyLabel.getPreferredSize().getWidth()));
        panel.add(usernameKeyText, createSimpleConstraints(5, 1, 3));

        panel.add(passwordKeyLabel, createLabelConstraints(6, 0, passwordKeyLabel.getPreferredSize().getWidth()));
        panel.add(passwordKeyText, createSimpleConstraints(6, 1, 3));

        secretType.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                value = ((SecretType) value).getText();
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        });
        secretType.addActionListener(e -> {
            switch ((SecretType) secretType.getSelectedItem()) {
                case DYNAMIC_ROLE:
                case STATIC_ROLE:
                    usernameKeyText.setEnabled(false);
                    passwordKeyText.setEnabled(false);
                    break;

                case KV1:
                case KV2:
                    usernameKeyText.setEnabled(true);
                    passwordKeyText.setEnabled(true);
                    break;
            }
        });
    }

    @Override
    public void save(@NotNull final DatabaseConnectionConfig config, final boolean copyCredentials) {
        config.setAdditionalProperty(PROP_SECRET, secretText.getText());
        config.setAdditionalProperty(PROP_ADDRESS, addressText.getText());
        config.setAdditionalProperty(PROP_TOKEN_FILE, tokenFileText.getText());
        config.setAdditionalProperty(PROP_CERTIFICATE, certificateText.getText());
        config.setAdditionalProperty(PROP_SECRET_TYPE, ((SecretType) secretType.getSelectedItem()).name());
        config.setAdditionalProperty(PROP_USERNAME_KEY, usernameKeyText.getText());
        config.setAdditionalProperty(PROP_PASSWORD_KEY, passwordKeyText.getText());
    }

    @Override
    public void reset(@NotNull final DatabaseConnectionPoint config, final boolean resetCredentials) {
        addressText.setText(config.getAdditionalProperty(PROP_ADDRESS));
        secretText.setText(config.getAdditionalProperty(PROP_SECRET));
        tokenFileText.setText(config.getAdditionalProperty(PROP_TOKEN_FILE));
        certificateText.setText(config.getAdditionalProperty(PROP_CERTIFICATE));
        if (config.getAdditionalProperty(PROP_SECRET_TYPE) != null && !config.getAdditionalProperty(PROP_SECRET_TYPE).isBlank()) {
            secretType.setSelectedItem(SecretType.valueOf(config.getAdditionalProperty(PROP_SECRET_TYPE)));
        } else {
            secretType.setSelectedItem(SecretType.DYNAMIC_ROLE);
        }
        usernameKeyText.setText(config.getAdditionalProperty(PROP_USERNAME_KEY));
        passwordKeyText.setText(config.getAdditionalProperty(PROP_PASSWORD_KEY));
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
