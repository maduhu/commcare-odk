package org.commcare.dalvik.activities;

import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.commcare.android.framework.UiElement;
import org.commcare.android.util.InstallAndUpdateUtils;
import org.commcare.dalvik.R;

import java.util.Date;

/**
 * Controls the UI for the upgrade activity.
 *
 * @author Phillip Mates (pmates@dimagi.com)
 */
class UpgradeUiController {
    @UiElement(R.id.check_for_upgrade_button)
    private Button checkUpgradeButton;

    @UiElement(R.id.stop_upgrade_download_button)
    private Button stopUpgradeButton;

    @UiElement(R.id.install_upgrade_button)
    private Button installUpgradeButton;

    @UiElement(R.id.upgrade_progress_bar)
    private ProgressBar progressBar;

    @UiElement(R.id.pending_upgrade_status_text)
    private TextView pendingUpgradeStatus;

    @UiElement(R.id.current_version_text)
    private TextView currentVersionText;

    @UiElement(R.id.upgrade_progress_text)
    private TextView progressText;

    private final UpgradeActivity activity;

    public UpgradeUiController(UpgradeActivity upgradeActivity) {
        activity = upgradeActivity;

        setupUi();
    }

    private void setupUi() {
        // title = Localization.get("updates.title");
        // message = Localization.get("updates.checking");
        activity.setContentView(R.layout.upgrade_activity);

        progressBar = (ProgressBar)activity.findViewById(R.id.upgrade_progress_bar);
        progressText = (TextView)activity.findViewById(R.id.upgrade_progress_text);
        pendingUpgradeStatus =
            (TextView)activity.findViewById(R.id.pending_upgrade_status_text);
        currentVersionText =
                (TextView)activity.findViewById(R.id.current_version_text);

        setupButtonListeners();
    }

    private void setupButtonListeners() {
        checkUpgradeButton =
            (Button)activity.findViewById(R.id.check_for_upgrade_button);
        checkUpgradeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.startUpgradeCheck();
            }
        });

        stopUpgradeButton =
            (Button)activity.findViewById(R.id.stop_upgrade_download_button);
        stopUpgradeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.stopUpgradeCheck();
            }
        });

        installUpgradeButton =
            (Button)activity.findViewById(R.id.install_upgrade_button);
        installUpgradeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InstallAndUpdateUtils.performUpgradeFromStagedTable();
            }
        });
    }

    protected void idle() {
        checkUpgradeButton.setEnabled(true);
        stopUpgradeButton.setEnabled(false);
        installUpgradeButton.setEnabled(false);

        stopUpgradeButton.setText("Stop upgrade");
    }

    protected void downloading() {
        checkUpgradeButton.setEnabled(false);
        stopUpgradeButton.setEnabled(true);
        installUpgradeButton.setEnabled(false);

        stopUpgradeButton.setText("Stop upgrade");
    }

    protected void unappliedUpdateAvailable() {
        checkUpgradeButton.setEnabled(true);
        stopUpgradeButton.setEnabled(false);
        installUpgradeButton.setEnabled(true);

        stopUpgradeButton.setText("Stop upgrade");

        int version = InstallAndUpdateUtils.upgradeTableVersion();
        pendingUpgradeStatus.setText("Current version: " + Integer.toString(version));
    }

    protected void cancelling() {
        checkUpgradeButton.setEnabled(false);
        stopUpgradeButton.setEnabled(false);
        installUpgradeButton.setEnabled(false);

        stopUpgradeButton.setText("Cancelling task");
    }

    protected void error() {
        checkUpgradeButton.setEnabled(false);
        stopUpgradeButton.setEnabled(false);
        installUpgradeButton.setEnabled(false);

        stopUpgradeButton.setText("Stop upgrade");
    }

    protected void updateProgressText(String msg) {
        progressText.setText(msg);
    }

    protected void updateProgressBar(int currentProgress, int max) {
        progressBar.setProgress(currentProgress);
        progressBar.setMax(max);
    }

    public void setStatusText(int version, Date lastChecked) {
        String checkedMsg = "Last checked for updates: " + lastChecked.toString();
        String versionMsg = "Current version: " + Integer.toString(version);
        currentVersionText.setText(versionMsg + "\n" + checkedMsg);
    }
}