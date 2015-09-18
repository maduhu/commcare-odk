package org.commcare.dalvik.activities;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.commcare.android.database.global.models.ApplicationRecord;
import org.commcare.android.database.user.models.SessionStateDescriptor;
import org.commcare.android.javarosa.AndroidLogger;
import org.commcare.android.util.ACRAUtil;
import org.commcare.android.util.AndroidCommCarePlatform;
import org.commcare.android.util.SessionUnavailableException;
import org.commcare.dalvik.R;
import org.commcare.dalvik.application.AndroidShortcuts;
import org.commcare.dalvik.application.CommCareApp;
import org.commcare.dalvik.application.CommCareApplication;
import org.javarosa.core.services.Logger;
import org.javarosa.core.services.locale.Localization;

import java.text.SimpleDateFormat;

/**
 * Created by amstone326 on 9/17/15.
 */
public class CommCareDispatchActivity extends Activity {

    private static final int LOGIN_USER = 0;
    private static final int GET_COMMAND = 1;
    private static final int GET_CASE = 2;
    private static final int MODEL_RESULT = 4;
    public static final int INIT_APP = 8;
    private static final int GET_INCOMPLETE_FORM = 16;
    public static final int UPGRADE_APP = 32;
    private static final int REPORT_PROBLEM_ACTIVITY = 64;

    // The API allows for external calls. When this occurs, redispatch to their
    // activity instead of commcare.
    private boolean wasExternal = false;

    private AndroidCommCarePlatform platform;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finishIfNotRoot();
        if (savedInstanceState != null) {
            wasExternal = savedInstanceState.getBoolean("was_external");
        }
        ACRAUtil.registerAppData();
    }

    private void finishIfNotRoot() {
        //This is a workaround required by Android Bug #2373 -- An app launched from the
        //Google Play store has different intent flags than one from the App launcher,
        // which ruins the back stack and prevents the app from launching a high affinity task.
        if (!isTaskRoot()) {
            Intent intent = getIntent();
            String action = intent.getAction();
            if (intent.hasCategory(Intent.CATEGORY_LAUNCHER) && action != null && action.equals(Intent.ACTION_MAIN)) {
                finish();
                return;
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("was_external", wasExternal);
    }

    @Override
    protected void onRestoreInstanceState(Bundle inState) {
        super.onRestoreInstanceState(inState);
        if (inState.containsKey("was_external")) {
            wasExternal = inState.getBoolean("was_external");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (CommCareApplication._().getCurrentApp() != null) {
            platform = CommCareApplication._().getCommCarePlatform();
        }
        dispatchNextActivity();
    }

    private void checkForDbFailState() {
        int dbState = CommCareApplication._().getDatabaseState();
        if (dbState == CommCareApplication.STATE_MIGRATION_FAILED) {
            CommCareApplication._().triggerHandledAppExit(this,
                    getString(R.string.migration_definite_failure),
                    getString(R.string.migration_failure_title), false);
            return;
        } else if (dbState == CommCareApplication.STATE_MIGRATION_QUESTIONABLE) {
            CommCareApplication._().triggerHandledAppExit(this,
                    getString(R.string.migration_possible_failure),
                    getString(R.string.migration_failure_title), false);
            return;
        } else if (dbState == CommCareApplication.STATE_CORRUPTED) {
            handleDamagedApp();
        }
    }

    //decide if we should even be in the home activity
    private void dispatchNextActivity() {
        checkForDbFailState();
        CommCareApp currentApp = CommCareApplication._().getCurrentApp();

        // Path 1: There is a seated app
        if (currentApp != null) {
            ApplicationRecord currentRecord = currentApp.getAppRecord();

            // Note that the order in which these conditions are checked matters!!
            try {
                if (currentApp.getAppResourceState() == CommCareApplication.STATE_CORRUPTED) {
                    // Path 1a: The seated app is damaged or corrupted
                    handleDamagedApp();
                } else if (!currentRecord.isUsable()) {
                    // Path 1b: The seated app is unusable (means either it is archived or is
                    // missing its MM or both)
                    boolean unseated = handleUnusableApp(currentRecord);
                    if (unseated) {
                        // Recurse in order to make the correct decision based on the new state
                        dispatchHomeScreen();
                    }
                } else if (!CommCareApplication._().getSession().isActive()) {
                    // Path 1c: The user is not logged in
                    returnToLogin();
                } else if (this.getIntent().hasExtra(SESSION_REQUEST)) {
                    // Path 1d: CommCare was launched from an external app, with a session descriptor
                    handleExternalLaunch();
                } else if (this.getIntent().hasExtra(AndroidShortcuts.EXTRA_KEY_SHORTCUT)) {
                    // Path 1e: CommCare was launched from a shortcut
                    handleShortcutLaunch();
                } else if (CommCareApplication._().isUpdatePending()) {
                    // Path 1f: There is an update pending
                    handlePendingUpdate();
                } else if (CommCareApplication._().isSyncPending(false)) {
                    // Path 1g: There is a sync pending
                    handlePendingSync();
                } else {
                    // Path 1h: Display the normal home screen!
                    uiController.refreshView();
                }
            } catch (SessionUnavailableException sue) {
                launchLoginActivity();
            }
        }

        // Path 2: There is no seated app, so launch CommCareSetupActivity
        else {
            if (CommCareApplication._().usableAppsPresent()) {
                // This is BAD -- means we ended up at home screen with no seated app, but there
                // are other usable apps available. Should not be able to happen.
                Logger.log(AndroidLogger.TYPE_ERROR_WORKFLOW, "In CommCareHomeActivity with no" +
                        "seated app, but there are other usable apps available on the device.");
            }
            launchSetupActivity();
        }
    }

    // region: activity dispatch methods

    private void launchLoginActivity() {
        Intent i = new Intent(this.getApplicationContext(), LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        this.startActivityForResult(i, LOGIN_USER);
    }

    private void launchSetupActivity() {
        Intent i = new Intent(getApplicationContext(), CommCareSetupActivity.class);
        this.startActivityForResult(i, INIT_APP);
    }

    // endregion

    // region: private helper methods used by dispatchHomeScreen(), to prevent it from being one
    // extremely long method

    private void handleDamagedApp() {
        if (!CommCareApplication._().isStorageAvailable()) {
            createNoStorageDialog();
        } else {
            // See if we're logged in. If so, prompt for recovery.
            try {
                CommCareApplication._().getSession();
                showDialog(DIALOG_CORRUPTED);
            } catch(SessionUnavailableException e) {
                // Otherwise, log in first
                returnToLogin();
            }
        }
    }

    /**
     *
     * @param record the ApplicationRecord corresponding to the seated, unusable app
     * @return if the unusable app was unseated by this method
     */
    private boolean handleUnusableApp(ApplicationRecord record) {
        if (record.isArchived()) {
            // If the app is archived, unseat it and try to seat another one
            CommCareApplication._().unseat(record);
            CommCareApplication._().initFirstUsableAppRecord();
            return true;
        }
        else {
            // This app has unvalidated MM
            if (CommCareApplication._().usableAppsPresent()) {
                // If there are other usable apps, unseat it and seat another one
                CommCareApplication._().unseat(record);
                CommCareApplication._().initFirstUsableAppRecord();
                return true;
            } else {
                handleUnvalidatedApp();
                return false;
            }
        }
    }

    /**
     * Handles the case where the seated app is unvalidated and there are no other usable apps
     * to seat instead -- Either calls out to verification activity or quits out of the app
     */
    private void handleUnvalidatedApp() {
        if (CommCareApplication._().shouldSeeMMVerification()) {
            Intent i = new Intent(this, CommCareVerificationActivity.class);
            this.startActivityForResult(i, MISSING_MEDIA_ACTIVITY);
        } else {
            // Means that there are no usable apps, but there are multiple apps who all don't have
            // MM verified -- show an error message and shut down
            CommCareApplication._().triggerHandledAppExit(this,
                    Localization.get("multiple.apps.unverified.message"),
                    Localization.get("multiple.apps.unverified.title"));
        }

    }

    private void handleExternalLaunch() {
        wasExternal = true;
        String sessionRequest = this.getIntent().getStringExtra(SESSION_REQUEST);
        SessionStateDescriptor ssd = new SessionStateDescriptor();
        ssd.fromBundle(sessionRequest);
        CommCareApplication._().getCurrentSessionWrapper().loadFromStateDescription(ssd);
        this.startNextFetch();
    }

    private void handleShortcutLaunch() {
        //We were launched in shortcut mode. Get the command and load us up.
        CommCareApplication._().getCurrentSession().setCommand(
                this.getIntent().getStringExtra(AndroidShortcuts.EXTRA_KEY_SHORTCUT));
        startNextFetch();
        //Only launch shortcuts once per intent
        this.getIntent().removeExtra(AndroidShortcuts.EXTRA_KEY_SHORTCUT);
    }

    private void handlePendingUpdate() {
        Logger.log(AndroidLogger.TYPE_MAINTENANCE, "Auto-Update Triggered");

        //Create the update intent
        Intent i = new Intent(getApplicationContext(), CommCareSetupActivity.class);
        SharedPreferences prefs = CommCareApplication._().getCurrentApp().getAppPreferences();
        String ref = prefs.getString("default_app_server", null);

        i.putExtra(CommCareSetupActivity.KEY_PROFILE_REF, ref);
        i.putExtra(CommCareSetupActivity.KEY_UPGRADE_MODE, true);
        i.putExtra(CommCareSetupActivity.KEY_AUTO, true);
        startActivityForResult(i, UPGRADE_APP);
    }

    private void handlePendingSync() {
        long lastSync = CommCareApplication._().getCurrentApp().getAppPreferences().getLong("last-ota-restore", 0);
        String footer = lastSync == 0 ? "never" : SimpleDateFormat.getDateTimeInstance().format(lastSync);
        Logger.log(AndroidLogger.TYPE_USER, "autosync triggered. Last Sync|" + footer);
        uiController.refreshView();

        //Send unsent forms first. If the process detects unsent forms
        //it will sync after the are submitted
        if(!this.checkAndStartUnsentTask(true)) {
            //If there were no unsent forms to be sent, we should immediately
            //trigger a sync
            this.syncData(false);
        }
    }

    private void createNoStorageDialog() {
        CommCareApplication._().triggerHandledAppExit(this, Localization.get("app.storage.missing.message"), Localization.get("app.storage.missing.title"));
    }

    // endregion

}
