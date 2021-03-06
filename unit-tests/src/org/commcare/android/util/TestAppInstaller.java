package org.commcare.android.util;

import org.commcare.android.database.app.models.UserKeyRecord;
import org.commcare.android.database.global.models.ApplicationRecord;
import org.commcare.android.database.user.DemoUserBuilder;
import org.commcare.android.database.user.models.User;
import org.commcare.android.mocks.CommCareTaskConnectorFake;
import org.commcare.android.tasks.ManageKeyRecordTask;
import org.commcare.android.tasks.ResourceEngineTask;
import org.commcare.dalvik.application.CommCareApp;
import org.commcare.dalvik.application.CommCareApplication;
import org.commcare.dalvik.services.CommCareSessionService;
import org.javarosa.core.util.PropertyUtils;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;

/**
 * Functionality to install an app from local storage, create a test user, log
 * into a user session
 *
 * @author Phillip Mates (pmates@dimagi.com).
 */
public class TestAppInstaller {
    private final String username;
    private final String password;
    private final String resourceFilepath;

    private final CommCareTaskConnectorFake<Object> fakeConnector =
            new CommCareTaskConnectorFake<>();


    public TestAppInstaller(String resourceFilepath,
                            String username,
                            String password) {
        this.resourceFilepath = resourceFilepath;
        this.username = username;
        this.password = password;
    }

    public void installAppAndLogin() {
        installApp();

        UserKeyRecord keyRecord = buildTestUser();

        startSessionService(keyRecord);
    }

    private void installApp() {
        ApplicationRecord newRecord =
                new ApplicationRecord(PropertyUtils.genUUID().replace("-", ""),
                        ApplicationRecord.STATUS_UNINITIALIZED);

        CommCareApp app = new CommCareApp(newRecord);
        ResourceEngineTask<Object> task =
                new ResourceEngineTask<Object>(false, app, false, -1, false) {
                    @Override
                    protected void deliverResult(Object receiver,
                                                 ResourceEngineOutcomes result) {
                    }

                    @Override
                    protected void deliverUpdate(Object receiver,
                                                 int[]... update) {
                    }

                    @Override
                    protected void deliverError(Object receiver,
                                                Exception e) {
                        throw new RuntimeException("App failed to install during test");
                    }
                };
        task.connect(fakeConnector);
        task.execute(resourceFilepath);

        Robolectric.flushBackgroundThreadScheduler();
        Robolectric.flushForegroundThreadScheduler();
    }

    private UserKeyRecord buildTestUser() {
        CommCareApp ccApp = CommCareApplication._().getCurrentApp();
        DemoUserBuilder.buildTestUser(RuntimeEnvironment.application,
                ccApp,
                username, password);

        return ManageKeyRecordTask.getCurrentValidRecord(ccApp, username, password, true);
    }

    private void startSessionService(UserKeyRecord keyRecord) {
        // manually create/setup session service because robolectric doesn't
        // really support services
        CommCareSessionService ccService = new CommCareSessionService();
        ccService.createCipherPool();
        ccService.prepareStorage(keyRecord.unWrapKey(password), keyRecord);
        ccService.startSession(getUserFromDb(ccService, keyRecord));

        CommCareApplication._().setTestingService(ccService);
    }

    private User getUserFromDb(CommCareSessionService ccService, UserKeyRecord keyRecord) {
        for (User u : CommCareApplication._().getRawStorage("USER", User.class, ccService.getUserDbHandle())) {
            if (keyRecord.getUsername().equals(u.getUsername())) {
                return u;
            }
        }
        return null;
    }
}
