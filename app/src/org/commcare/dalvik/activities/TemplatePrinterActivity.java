package org.commcare.dalvik.activities;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.commcare.android.adapters.PdfPrintDocumentAdapter;
import org.commcare.android.tasks.TemplatePrinterTask;
import org.commcare.android.tasks.TemplatePrinterTask.PopulateListener;
import org.commcare.android.util.TemplatePrinterEncryptedStream;
import org.commcare.android.util.TemplatePrinterUtils;
import org.commcare.dalvik.R;
import org.commcare.dalvik.application.CommCareApplication;
import org.commcare.dalvik.preferences.CommCarePreferences;
import org.javarosa.core.reference.InvalidReferenceException;
import org.javarosa.core.reference.ReferenceManager;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintJob;
import android.print.PrintManager;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Intermediate activity which populates a .DOCX/.ODT template
 * with data before sending it off to a document viewer app
 * capable of printing.
 * 
 * @author Richard Lu
 * @author amstone
 */
public class TemplatePrinterActivity extends Activity implements PopulateListener {

    /**
     * The name of the file that is written to in TemplatePrinterTask and then read from in
     * PdfPrintDocumentAdapter, Stored WITHOUT an extension, because we want it to be .docx when
     * writing to it, and then .pdf when reading from it.
     */
    public final static String PATH_NO_EXTENSION = CommCareApplication._().getTempFilePath();

    /**
     * Unique name to use for the print job name
     */
    private static String jobName;

    /**
     * Provides the encrypted output stream with which to write the filled-in template file,
     * and the input stream with which to decrypt and read back from that file in order to print
     */
    private TemplatePrinterEncryptedStream stream;

    /**
     * Used to hold an instance of the WebView object being printed, so that is it not garbage
     * collected before the print job is created
     */
    private WebView mWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_template_printer);

        //Check to make sure we are targeting API 19 or above, which is where print is supported
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            showErrorDialog(getString(R.string.print_not_supported));
        }

        Bundle data = getIntent().getExtras();
        //Check to make sure key-value data has been passed with the intent
        if (data == null) {
            showErrorDialog(R.string.no_data);
        }

        // Get the case number, which may be sent with the intent bundle -- For purposes of
        // creating the job name. If not included, will just not be used.
        String caseNum = data.getString("cc:case_num");

        //Check if a doc location is coming in from the Intent
        //Will return a reference of format jr://... if it has been set
        String path = data.getString("cc:print_template_reference");
        if (path != null) {
            try {
                path = ReferenceManager._().DeriveReference(path).getLocalURI();
                preparePrintDoc(path, caseNum);
            } catch (InvalidReferenceException e) {
                showErrorDialog(getString(R.string.template_invalid, path));
            }
        } else {
            //Try to use the document location that was set in Settings menu
            SharedPreferences prefs = CommCareApplication._().getCurrentApp().getAppPreferences();
            path = prefs.getString(CommCarePreferences.PRINT_DOC_LOCATION, "");
            if ("".equals(path)) {
                showErrorDialog(getString(R.string.template_not_set));
            } else {
                preparePrintDoc(path, caseNum);
            }
        }
    }

    private void preparePrintDoc(String path, String caseNum) {
        String extension = TemplatePrinterUtils.getExtension(path);
        File templateFile = new File(path);

        if (TemplatePrinterTask.DocTypeEnum.isSupportedExtension(extension)
                && templateFile.exists()) {

            generateJobName(templateFile, caseNum);

            // Create the EncryptedStream that will be used by TemplatePrinterTask to write to a
            // file, and by PdfPrintDocumentAdapter to read back from that file
            this.stream = new TemplatePrinterEncryptedStream(PATH_NO_EXTENSION, ".html");

            new TemplatePrinterTask(
                    templateFile,
                    this.stream,
                    getIntent().getExtras(),
                    this
            ).execute();
        } else {
            showErrorDialog(getString(R.string.template_invalid, path));
        }
    }

    private void generateJobName(File templateFile, String caseNum) {
        String inputName = templateFile.getName().substring(0, templateFile.getName().lastIndexOf('.'));
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        String dateString = sdf.format(new Date());
        if (caseNum != null) {
            jobName = inputName + "_" + caseNum + "_" + dateString;
        } else {
            jobName = inputName + "_" + dateString;
        }
    }

    @Override
    public void onError(String message) {
        showErrorDialog(message);
    }

    /**
     * Called when TemplatePrinterTask finishes successfully, meaning a .html file of the
     * filled-out template has been created and saved successfully
     */
    @Override
    public void onFinished() {
        doHtmlPrint(PATH_NO_EXTENSION + ".html");
    }

    private void showErrorDialog(int messageResId) {
        showErrorDialog(getString(messageResId));
    }

    /**
     * Displays an error dialog with the specified message.
     * Activity will quit upon exiting the dialog.
     *
     * @param message Error message
     */
    private void showErrorDialog(String message) {

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this)
                .setTitle(R.string.error_occured)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(
                        R.string.ok,
                        new DialogInterface.OnClickListener() {

                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                                finish();
                            }
                        }
                );
        dialogBuilder.show();
    }

    /*@TargetApi(Build.VERSION_CODES.KITKAT)
    private void executePrint() {
        PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        PdfPrintDocumentAdapter adapter = new PdfPrintDocumentAdapter(this, jobName, this.stream);
        printManager.print(jobName, adapter, null);
    }*/


    /**
     * Prepares a WebView out of an html document, which can then be printed by the Android print
     * framework
     *
     * Source: https://developer.android.com/training/printing/html-docs.html
     *
     * @param path path to the html document to print
     */
    private void doHtmlPrint(String path) {
        // Create a WebView object specifically for printing
        WebView webView = new WebView(this);
        webView.setWebViewClient(new WebViewClient() {

            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                createWebPrintJob(view);
                mWebView = null;
            }
        });
        try {
            String htmlDocString = TemplatePrinterUtils.docToString(new File(path));
            webView.loadDataWithBaseURL(null, htmlDocString, "text/HTML", "UTF-8", null);

            // Keep reference to WebView object until PrintDocumentAdapter is passed to PrintManager
            mWebView = webView;
        } catch (IOException e) {
            showErrorDialog("Could not read from generated HTML doc in order to print");
        }

    }

    /**
     * Starts a print job for a WebView
     *
     * Source: https://developer.android.com/training/printing/html-docs.html
     *
     * @param v the WebView to be printed
     */
    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void createWebPrintJob(WebView v) {

        // Get a PrintManager instance
        PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);

        // Get a print adapter instance
        PrintDocumentAdapter printAdapter = v.createPrintDocumentAdapter();

        // Create a print job with name and adapter instance
        PrintJob printJob = printManager.print(jobName, printAdapter,
                new PrintAttributes.Builder().build());
    }

}
