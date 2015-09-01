package org.odk.collect.android.activities;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.commcare.android.framework.CommCareActivity;
import org.commcare.android.util.StringUtils;
import org.commcare.dalvik.R;
import org.commcare.dalvik.odk.provider.FormsProviderAPI;
import org.javarosa.core.model.Constants;
import org.javarosa.core.model.FormIndex;
import org.javarosa.core.services.Logger;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.xpath.XPathTypeMismatchException;
import org.odk.collect.android.logic.FormController;
import org.odk.collect.android.preferences.PreferencesActivity;
import org.odk.collect.android.views.ODKView;
import org.odk.collect.android.widgets.QuestionWidget;

/**
 * @author Phillip Mates (pmates@dimagi.com)
 */
public class FormEntryUiController {
    private static final String TAG = FormEntryUiController.class.getSimpleName();
    private ViewGroup mViewPane;

    private FormEntryActivity activity;

    private Animation mInAnimation;
    private Animation mOutAnimation;

    enum AnimationType {
        LEFT, RIGHT, FADE
    }

    FormEntryUiController(FormEntryActivity activity) {
        this.activity = activity;
        setupUI();
    }

    private void setupUI() {
        activity.setContentView(R.layout.screen_form_entry);
        setNavBarVisibility();

        ImageButton nextButton = (ImageButton)activity.findViewById(R.id.nav_btn_next);
        ImageButton prevButton = (ImageButton)activity.findViewById(R.id.nav_btn_prev);

        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!"done".equals(v.getTag())) {
                    showNextView();
                } else {
                    activity.triggerUserFormComplete();
                }
            }
        });

        prevButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!"quit".equals(v.getTag())) {
                    showPreviousView();
                } else {
                    activity.triggerUserQuitInput();
                }
            }
        });

        mViewPane = (ViewGroup)activity.findViewById(R.id.form_entry_pane);
        activity.clearFields();

        mInAnimation = null;
        mOutAnimation = null;
    }

    public void setNavBarVisibility() {
        //Make sure the nav bar visibility is set
        int navBarVisibility = PreferencesActivity.getProgressBarMode(activity).useNavigationBar() ? View.VISIBLE : View.GONE;
        View nav = activity.findViewById(R.id.nav_pane);
        if (nav.getVisibility() != navBarVisibility) {
            nav.setVisibility(navBarVisibility);
            activity.findViewById(R.id.nav_badge_border_drawer).setVisibility(navBarVisibility);
            activity.findViewById(R.id.nav_badge).setVisibility(navBarVisibility);
        }
    }

    /**
     * Determines what should be displayed on the screen. Possible options are: a question, an ask
     * repeat dialog, or the submit screen. Also saves answers to the data model after checking
     * constraints.
     */
    protected void showNextView() {
        showNextView(false);
    }

    private void showNextView(boolean resuming) {
        if (!resuming && activity.atEndOfForm()) {
            //See if we should stop displaying the start screen
            CheckBox stopShowingIntroScreen = (CheckBox)mCurrentView.findViewById(R.id.screen_form_entry_start_cbx_dismiss);
            //Not sure why it would, but maybe timing issues?
            if (stopShowingIntroScreen != null) {
                if (stopShowingIntroScreen.isChecked()) {
                    //set it!
                    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                    sharedPreferences.edit().putBoolean(PreferencesActivity.KEY_SHOW_START_SCREEN, false).commit();
                }
            }
        }

        if (activity.currentPromptIsQuestion()) {
            if (!activity.saveAnswersForCurrentScreen(FormEntryActivity.EVALUATE_CONSTRAINTS)) {
                // A constraint was violated so a dialog should be showing.
                return;
            }
        }

        if (!activity.atEndOfForm()) {
            int event;

            try {
                group_skip:
                do {
                    event = mFormController.stepToNextEvent(FormController.STEP_OVER_GROUP);
                    switch (event) {
                        case FormEntryController.EVENT_QUESTION:
                        case FormEntryController.EVENT_END_OF_FORM:
                            View next = activity.createView(event);
                            if (!resuming) {
                                showView(next, AnimationType.RIGHT);
                            } else {
                                showView(next, AnimationType.FADE, false);
                            }
                            break group_skip;
                        case FormEntryController.EVENT_PROMPT_NEW_REPEAT:
                            createRepeatDialog();
                            break group_skip;
                        case FormEntryController.EVENT_GROUP:
                            //We only hit this event if we're at the _opening_ of a field
                            //list, so it seems totally fine to do it this way, technically
                            //though this should test whether the index is the field list
                            //host.
                            if (activity.atNonEmptyFieldList()) {
                                View nextGroupView = createView(event);
                                if (!resuming) {
                                    showView(nextGroupView, AnimationType.RIGHT);
                                } else {
                                    showView(nextGroupView, AnimationType.FADE, false);
                                }
                                break group_skip;
                            }
                            // otherwise it's not a field-list group, so just skip it
                            break;
                        case FormEntryController.EVENT_REPEAT:
                            Log.i(TAG, "repeat: " + mFormController.getFormIndex().getReference());
                            // skip repeats
                            break;
                        case FormEntryController.EVENT_REPEAT_JUNCTURE:
                            Log.i(TAG, "repeat juncture: "
                                    + mFormController.getFormIndex().getReference());
                            // skip repeat junctures until we implement them
                            break;
                        default:
                            Log.w(TAG,
                                    "JavaRosa added a new EVENT type and didn't tell us... shame on them.");
                            break;
                    }
                } while (event != FormEntryController.EVENT_END_OF_FORM);
            } catch (XPathTypeMismatchException e) {
                Logger.exception(e);
                CommCareActivity.createErrorDialog(this, e.getMessage(), EXIT);
            }
        } else {
            mBeenSwiped = false;
        }
    }

    /**
     * Displays the View specified by the parameter 'next', animating both the current view and next
     * appropriately given the AnimationType. Also updates the progress bar.
     */
    private void showView(View next, AnimationType from) {
        showView(next, from, true);
    }

    private void showView(View next, AnimationType from, boolean animateLastView) {
        switch (from) {
            case RIGHT:
                mInAnimation = AnimationUtils.loadAnimation(this, R.anim.push_left_in);
                mOutAnimation = AnimationUtils.loadAnimation(this, R.anim.push_left_out);
                break;
            case LEFT:
                mInAnimation = AnimationUtils.loadAnimation(this, R.anim.push_right_in);
                mOutAnimation = AnimationUtils.loadAnimation(this, R.anim.push_right_out);
                break;
            case FADE:
                mInAnimation = AnimationUtils.loadAnimation(this, R.anim.fade_in);
                mOutAnimation = AnimationUtils.loadAnimation(this, R.anim.fade_out);
                break;
        }

        if (mCurrentView != null) {
            if (animateLastView) {
                mCurrentView.startAnimation(mOutAnimation);
            }
            mViewPane.removeView(mCurrentView);
        }

        mInAnimation.setAnimationListener(activity);

        RelativeLayout.LayoutParams lp =
                new RelativeLayout.LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);

        mCurrentView = next;
        mViewPane.addView(mCurrentView, lp);

        mCurrentView.startAnimation(mInAnimation);

        FrameLayout header = (FrameLayout)findViewById(R.id.form_entry_header);

        TextView groupLabel = ((TextView)header.findViewById(R.id.form_entry_group_label));

        header.setVisibility(View.GONE);
        groupLabel.setVisibility(View.GONE);

        if (mCurrentView instanceof ODKView) {
            ((ODKView)mCurrentView).setFocus(this);

            SpannableStringBuilder groupLabelText = ((ODKView)mCurrentView).getGroupLabel();

            if (groupLabelText != null && !groupLabelText.toString().trim().equals("")) {
                groupLabel.setText(groupLabelText);
                header.setVisibility(View.VISIBLE);
                groupLabel.setVisibility(View.VISIBLE);
            }
        } else {
            InputMethodManager inputManager =
                    (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            inputManager.hideSoftInputFromWindow(mCurrentView.getWindowToken(), 0);
        }
    }

    /**
     * Determines what should be displayed between a question, or the start screen and displays the
     * appropriate view. Also saves answers to the data model without checking constraints.
     */
    protected void showPreviousView() {
        // The answer is saved on a back swipe, but question constraints are ignored.
        if (activity.currentPromptIsQuestion()) {
            activity.saveAnswersForCurrentScreen(FormEntryActivity.DO_NOT_EVALUATE_CONSTRAINTS);
        }

        FormIndex startIndex = mFormController.getFormIndex();
        FormIndex lastValidIndex = startIndex;

        if (mFormController.getEvent() != FormEntryController.EVENT_BEGINNING_OF_FORM) {
            int event = mFormController.stepToPreviousEvent();

            //Step backwards until we either find a question, the beginning of the form,
            //or a field list with valid questions inside
            while (event != FormEntryController.EVENT_BEGINNING_OF_FORM
                    && event != FormEntryController.EVENT_QUESTION
                    && !(event == FormEntryController.EVENT_GROUP
                    && mFormController.indexIsInFieldList() && mFormController
                    .getQuestionPrompts().length != 0)) {
                event = mFormController.stepToPreviousEvent();
                lastValidIndex = mFormController.getFormIndex();
            }

            //check if we're at the beginning and not doing the whole "First screen" thing
            if (event == FormEntryController.EVENT_BEGINNING_OF_FORM && !PreferencesActivity.showFirstScreen(this)) {

                //If so, we can't go all the way back here, so we've gotta hit the last index that was valid
                mFormController.jumpToIndex(lastValidIndex);

                //Did we jump at all? (not sure how we could have, but there might be a mismatch)
                if (lastValidIndex.equals(startIndex)) {
                    //If not, don't even bother changing the view.
                    //NOTE: This needs to be the same as the
                    //exit condition below, in case either changes
                    mBeenSwiped = false;
                    activity.triggerUserQuitInput();
                    return;
                }

                //We might have walked all the way back still, which isn't great,
                //so keep moving forward again until we find it
                if (lastValidIndex.isBeginningOfFormIndex()) {
                    //there must be a repeat between where we started and the beginning of hte form, walk back up to it
                    this.showNextView(true);
                    return;
                }
            }
            View next = createView(event);
            showView(next, AnimationType.LEFT);

        } else {
            //NOTE: this needs to match the exist condition above
            //when there is no start screen
            mBeenSwiped = false;
            activity.triggerUserQuitInput();
        }
    }

    /**
     * Refreshes the current view. the controller and the displayed view can get out of sync due to
     * dialogs and restarts caused by screen orientation changes, so they're resynchronized here.
     */
    protected void refreshCurrentView() {
        refreshCurrentView(true);
    }

    /**
     * Refreshes the current view. the controller and the displayed view can get out of sync due to
     * dialogs and restarts caused by screen orientation changes, so they're resynchronized here.
     */
    protected void refreshCurrentView(boolean animateLastView) {
        if (mFormController == null) {
            throw new RuntimeException("Form state is lost! Cannot refresh current view. This shouldn't happen, please submit a bug report.");
        }
        int event = mFormController.getEvent();

        // When we refresh, repeat dialog state isn't maintained, so step back to the previous
        // question.
        // Also, if we're within a group labeled 'field list', step back to the beginning of that
        // group.
        // That is, skip backwards over repeat prompts, groups that are not field-lists,
        // repeat events, and indexes in field-lists that is not the containing group.
        while (event == FormEntryController.EVENT_PROMPT_NEW_REPEAT
                || (event == FormEntryController.EVENT_GROUP && !mFormController.indexIsInFieldList())
                || event == FormEntryController.EVENT_REPEAT
                || (mFormController.indexIsInFieldList() && !(event == FormEntryController.EVENT_GROUP))) {
            event = mFormController.stepToPreviousEvent();
        }

        //If we're at the beginning of form event, but don't show the screen for that, we need
        //to get the next valid screen
        if (event == FormEntryController.EVENT_BEGINNING_OF_FORM && !PreferencesActivity.showFirstScreen(this)) {
            this.showNextView(true);
        } else {
            View current = createView(event);
            showView(current, AnimationType.FADE, animateLastView);
        }
    }

    /**
     * Creates a view given the View type and an event
     */
    private View createView(int event) {
        activity.setTitle(activity.getHeaderString());
        switch (event) {
            case FormEntryController.EVENT_BEGINNING_OF_FORM:
                View startView = View.inflate(activity, R.layout.form_entry_start, null);
                activity.setTitle(activity.getHeaderString());

                ((TextView)startView.findViewById(R.id.description)).setText(StringUtils.getStringSpannableRobust(activity, R.string.enter_data_description, mFormController.getFormTitle()));

                ((CheckBox)startView.findViewById(R.id.screen_form_entry_start_cbx_dismiss)).setText(StringUtils.getStringSpannableRobust(activity, R.string.form_entry_start_hide));

                ((TextView)startView.findViewById(R.id.screen_form_entry_advance_text)).setText(StringUtils.getStringSpannableRobust(activity, R.string.advance));

                ((TextView)startView.findViewById(R.id.screen_form_entry_backup_text)).setText(StringUtils.getStringSpannableRobust(activity, R.string.backup));

                Drawable image = null;
                String[] projection = {
                        FormsProviderAPI.FormsColumns.FORM_MEDIA_PATH
                };
                String selection = FormsProviderAPI.FormsColumns.FORM_FILE_PATH + "=?";
                String[] selectionArgs = {
                        mFormPath
                };

                Cursor c = null;
                String mediaDir = null;
                try {
                    c = activity.getContentResolver().query(formProviderContentURI, projection, selection, selectionArgs, null);
                    if (c.getCount() < 1) {
                        CommCareActivity.createErrorDialog(this, "Form doesn't exist", EXIT);
                        return new View(this);
                    } else {
                        c.moveToFirst();
                        mediaDir = c.getString(c.getColumnIndex(FormsProviderAPI.FormsColumns.FORM_MEDIA_PATH));
                    }
                } finally {
                    if (c != null) {
                        c.close();
                    }
                }

                // attempt to load the form-specific logo...
                // this is arbitrarily silly
                BitmapDrawable bitImage = new BitmapDrawable(mediaDir + "/form_logo.png");

                if (bitImage.getBitmap() != null &&
                        bitImage.getIntrinsicHeight() > 0 &&
                        bitImage.getIntrinsicWidth() > 0) {
                    image = bitImage;
                }

                if (image == null) {
                    ((ImageView)startView.findViewById(R.id.form_start_bling))
                            .setVisibility(View.GONE);
                } else {
                    ((ImageView)startView.findViewById(R.id.form_start_bling))
                            .setImageDrawable(image);
                }

                return startView;
            case FormEntryController.EVENT_END_OF_FORM:
                View endView = View.inflate(this, R.layout.form_entry_end, null);
                ((TextView)endView.findViewById(R.id.description)).setText(StringUtils.getStringSpannableRobust(this, R.string.save_enter_data_description,
                        mFormController.getFormTitle()));

                // checkbox for if finished or ready to send
                final CheckBox instanceComplete = ((CheckBox)endView.findViewById(R.id.mark_finished));
                instanceComplete.setText(StringUtils.getStringSpannableRobust(this, R.string.mark_finished));

                //If incomplete is not enabled, make sure this box is checked.
                instanceComplete.setChecked(!mIncompleteEnabled || isInstanceComplete(true));

                if (mFormController.isFormReadOnly() || !mIncompleteEnabled) {
                    instanceComplete.setVisibility(View.GONE);
                }

                // edittext to change the displayed name of the instance
                final EditText saveAs = (EditText)endView.findViewById(R.id.save_name);

                //TODO: Figure this out based on the content provider or some part of the context
                saveAs.setVisibility(View.GONE);
                endView.findViewById(R.id.save_form_as).setVisibility(View.GONE);

                // disallow carriage returns in the name
                InputFilter returnFilter = new InputFilter() {
                    public CharSequence filter(CharSequence source, int start, int end,
                                               Spanned dest, int dstart, int dend) {
                        for (int i = start; i < end; i++) {
                            if (Character.getType((source.charAt(i))) == Character.CONTROL) {
                                return "";
                            }
                        }
                        return null;
                    }
                };
                saveAs.setFilters(new InputFilter[]{
                        returnFilter
                });

                String saveName = activity.getDefaultFormTitle();

                saveAs.setText(saveName);

                // Create 'save' button
                Button button = (Button)endView.findViewById(R.id.save_exit_button);
                if (mFormController.isFormReadOnly()) {
                    button.setText(StringUtils.getStringSpannableRobust(this, R.string.exit));
                    button.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            activity.finishReturnInstance();
                        }
                    });

                } else {
                    button.setText(StringUtils.getStringSpannableRobust(this, R.string.quit_entry));
                    button.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // Form is marked as 'saved' here.
                            if (saveAs.getText().length() < 1) {
                                Toast.makeText(activity, StringUtils.getStringSpannableRobust(activity, R.string.save_as_error),
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                activity.saveDataToDisk(EXIT,
                                        instanceComplete.isChecked(),
                                        saveAs.getText().toString(),
                                        false);
                            }
                        }
                    });

                }

                return endView;
            case FormEntryController.EVENT_GROUP:
            case FormEntryController.EVENT_QUESTION:
                ODKView odkv;
                // should only be a group here if the event_group is a field-list
                try {
                    odkv =
                            new ODKView(activity, mFormController.getQuestionPrompts(),
                                    mFormController.getGroupsForCurrentIndex(),
                                    mFormController.getWidgetFactory(), activity);
                    Log.i(TAG, "created view for group");
                } catch (RuntimeException e) {
                    Logger.exception(e);
                    CommCareActivity.createErrorDialog(this, e.getMessage(), EXIT);
                    // this is badness to avoid a crash.
                    // really a next view should increment the formcontroller, create the view
                    // if the view is null, then keep the current view and pop an error.
                    return new View(this);
                }

                // Makes a "clear answer" menu pop up on long-click of
                // select-one/select-multiple questions
                for (QuestionWidget qw : odkv.getWidgets()) {
                    if (!qw.getPrompt().isReadOnly() &&
                            !mFormController.isFormReadOnly() &&
                            (qw.getPrompt().getControlType() == Constants.CONTROL_SELECT_ONE ||
                                    qw.getPrompt().getControlType() == Constants.CONTROL_SELECT_MULTI)) {
                        activity.registerForContextMenu(qw);
                    }
                }

                activity.updateNavigationCues(odkv);

                return odkv;
            default:
                Log.e(TAG, "Attempted to create a view that does not exist.");
                return null;
        }
    }
}
