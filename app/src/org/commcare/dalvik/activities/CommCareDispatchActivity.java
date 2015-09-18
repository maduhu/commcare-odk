package org.commcare.dalvik.activities;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.commcare.android.database.global.models.ApplicationRecord;
import org.commcare.android.database.user.models.SessionStateDescriptor;
import org.commcare.android.framework.CommCareActivity;
import org.commcare.android.javarosa.AndroidLogger;
import org.commcare.android.models.AndroidSessionWrapper;
import org.commcare.android.util.ACRAUtil;
import org.commcare.android.util.AndroidCommCarePlatform;
import org.commcare.android.util.SessionUnavailableException;
import org.commcare.dalvik.R;
import org.commcare.dalvik.application.AndroidShortcuts;
import org.commcare.dalvik.application.CommCareApp;
import org.commcare.dalvik.application.CommCareApplication;
import org.commcare.dalvik.preferences.DeveloperPreferences;
import org.commcare.suite.model.StackFrameStep;
import org.commcare.suite.model.Text;
import org.commcare.util.CommCareSession;
import org.commcare.util.SessionFrame;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.services.Logger;
import org.javarosa.core.services.locale.Localization;
import org.javarosa.xpath.XPathException;

import java.text.SimpleDateFormat;

/**
 * Created by amstone326 on 9/17/15.
 */
public class CommCareDispatchActivity extends CommCareActivity {

    private static final int LOGIN_USER = 0;
    private static final int GET_COMMAND = 1;
    private static final int GET_CASE = 2;
    private static final int MODEL_RESULT = 4;
    public static final int INIT_APP = 8;
    private static final int GET_INCOMPLETE_FORM = 16;
    public static final int UPGRADE_APP = 32;
    private static final int REPORT_PROBLEM_ACTIVITY = 64;

    /**
     * Request code for automatically validating media from home dispatch.
     * Should signal a return from CommCareVerificationActivity.
     */
    public static final int MISSING_MEDIA_ACTIVITY = 256;

    private static final int DIALOG_CORRUPTED = 1;

    private static final String SESSION_REQUEST = "ccodk_session_request";

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
        dispatchProperActivity();
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

    private void dispatchProperActivity() {
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
                        dispatchProperActivity();
                    }
                } else if (!CommCareApplication._().getSession().isActive()) {
                    // Path 1c: The user is not logged in
                    launchLoginActivity();
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
                    // Path 1h: Normal home activity launch
                    launchHomeActivity();
                }
            } catch (SessionUnavailableException sue) {
                launchLoginActivity();
            }
        }

        // Path 2: There is no seated app, so launch CommCareSetupActivity
        else {
            if (CommCareApplication._().usableAppsPresent()) {
                // This is BAD -- means that there is a usable app available which
                // aCommCareApplication did not seat. Should not be able to happen.
                Logger.log(AndroidLogger.TYPE_ERROR_WORKFLOW, "In CommCareDispatchActivity with no" +
                        "seated app, but there are other usable apps available on the device.");
            }
            launchSetupActivity();
        }
    }

    // region: private helper methods used by dispatchNextActivity(), to prevent it from being one
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
                launchLoginActivity();
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
            launchVerificationActivity();
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
        startNextFetch();
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

        //uiController.refreshView();

        // Send unsent forms first. If the process detects unsent forms it will sync after they
        // are submitted
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


    // region: activity dispatch methods

    private void launchLoginActivity() {
        Intent i = new Intent(getApplicationContext(), LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        this.startActivityForResult(i, LOGIN_USER);
    }

    private void launchHomeActivity() {
        Intent i = new Intent(getApplicationContext(), CommCareHomeActivity.class);
        this.startActivityForResult(i, INIT_APP);
    }

    private void launchSetupActivity() {
        Intent i = new Intent(getApplicationContext(), CommCareSetupActivity.class);
        this.startActivityForResult(i, INIT_APP);
    }

    private void launchVerificationActivity() {
        Intent i = new Intent(this, CommCareVerificationActivity.class);
        this.startActivityForResult(i, MISSING_MEDIA_ACTIVITY);
    }

    // endregion

    private Dialog createAskFixDialog() {
        //TODO: Localize this in theory, but really shift it to the upgrade/management state
        AlertDialog mAttemptFixDialog = new AlertDialog.Builder(this).create();

        mAttemptFixDialog.setTitle("Storage is Corrupt :/");
        mAttemptFixDialog.setMessage("Sorry, something really bad has happened, and the app can't start up. With your permission CommCare can try to repair itself if you have network access.");
        DialogInterface.OnClickListener attemptFixDialog = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int i) {
                switch (i) {
                    case DialogInterface.BUTTON_POSITIVE: // attempt repair
                        Intent intent = new Intent(CommCareDispatchActivity.this, RecoveryActivity.class);
                        startActivity(intent);
                        break;
                    case DialogInterface.BUTTON_NEGATIVE: // Shut down
                        CommCareDispatchActivity.this.finish();
                        break;
                }
            }
        };
        mAttemptFixDialog.setCancelable(false);
        mAttemptFixDialog.setButton(DialogInterface.BUTTON_POSITIVE, "Enter Recovery Mode", attemptFixDialog);
        mAttemptFixDialog.setButton(DialogInterface.BUTTON_NEGATIVE, "Shut Down", attemptFixDialog);

        return mAttemptFixDialog;
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        if (id == DIALOG_CORRUPTED) {
            return createAskFixDialog();
        } else return null;
    }

    /**
     * Polls the CommCareSession to determine what information is needed in order to proceed with
     * the next entry step in the session and then executes the action to get that info, OR
     * proceeds with trying to enter the form if no more info is needed
     */
    private void startNextFetch() {

        final AndroidSessionWrapper asw = CommCareApplication._().getCurrentSessionWrapper();
        CommCareSession session = asw.getSession();
        String needed = session.getNeededData();

        if (needed == null) {
            readyToProceed(asw);
        } else if (needed.equals(SessionFrame.STATE_COMMAND_ID)) {
            handleGetCommand(session);
        } else if (needed.equals(SessionFrame.STATE_DATUM_VAL)) {
            handleGetDatum(session);
        } else if (needed.equals(SessionFrame.STATE_DATUM_COMPUTED)) {
            handleCompute(asw);
        }
    }

    // region: private helper methods used by startNextFetch(), to prevent it from being one
    // extremely long method

    private void readyToProceed(final AndroidSessionWrapper asw) {
        EvaluationContext ec = asw.getEvaluationContext();
        //See if we failed any of our assertions
        Text text = asw.getSession().getCurrentEntry().getAssertions().getAssertionFailure(ec);
        if (text != null) {
            createErrorDialog(text.evaluate(ec), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int i) {
                    asw.getSession().stepBack();
                    CommCareHomeActivity.this.startNextFetch();
                }
            });
            return;
        }

        if(asw.getSession().getForm() == null) {
            if(asw.terminateSession()) {
                startNextFetch();
            } else {
                uiController.refreshView();
            }
        } else {
            startFormEntry(CommCareApplication._().getCurrentSessionWrapper());
        }
    }

    private void handleGetCommand(CommCareSession session) {
        Intent i;
        if (DeveloperPreferences.isGridMenuEnabled()) {
            i = new Intent(getApplicationContext(), MenuGrid.class);
        } else {
            i = new Intent(getApplicationContext(), MenuList.class);
        }
        i.putExtra(SessionFrame.STATE_COMMAND_ID, session.getCommand());
        startActivityForResult(i, GET_COMMAND);
    }

    private void handleGetDatum(CommCareSession session) {
        Intent i = new Intent(getApplicationContext(), EntitySelectActivity.class);
        i.putExtra(SessionFrame.STATE_COMMAND_ID, session.getCommand());
        StackFrameStep lastPopped = session.getPoppedStep();
        if (lastPopped != null && SessionFrame.STATE_DATUM_VAL.equals(lastPopped.getType())) {
            i.putExtra(EntitySelectActivity.EXTRA_ENTITY_KEY, lastPopped.getValue());
        }
        startActivityForResult(i, GET_CASE);
    }

    private void handleCompute(AndroidSessionWrapper asw) {
        EvaluationContext ec = asw.getEvaluationContext();
        try {
            asw.getSession().setComputedDatum(ec);
        } catch (XPathException e) {
            displayException(e);
        }
        startNextFetch();
    }

    // endregion

}
