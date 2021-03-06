package org.commcare.android.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.commcare.android.javarosa.AndroidLogger;
import org.javarosa.core.model.data.GeoPointData;
import org.javarosa.core.model.data.UncastData;
import org.javarosa.core.reference.InvalidReferenceException;
import org.javarosa.core.reference.Reference;
import org.javarosa.core.reference.ReferenceManager;
import org.javarosa.core.services.Logger;

import java.io.IOException;

/**
 * @author ctsims
 */
public class MediaUtil {
    public static final String FORM_VIDEO = "video";
    public static final String FORM_AUDIO = "audio";
    public static final String FORM_IMAGE = "image";
	public static Bitmap getScaledImageFromReference(String jrReference) {
		return getScaledImageFromReference(jrReference, 1);
	}
	
	/*
	 * returns the file referenced by jrReference scaled by scaleFactor. scaleFactor should be a power of two;
	 * for example, a scale factor of 4 returns an image that is 1/4 the width/height of the original, and 1/16th
	 * the size in pixels
	*/
	public static Bitmap getScaledImageFromReference(String jrReference, int scaleFactor) {
        //TODO: Eventually we'll want to be able to deal with dymanic resources here.
        try {

        Reference imageRef = ReferenceManager._().DeriveReference(jrReference);
        if(!imageRef.doesBinaryExist()) {
            return null;
        }
        
        BitmapFactory.Options options = new BitmapFactory.Options();

        options.inSampleSize = scaleFactor;
        
        return BitmapFactory.decodeStream(imageRef.getStream(), null, options);

        } catch(InvalidReferenceException ire) {
            Logger.log(AndroidLogger.TYPE_ERROR_CONFIG_STRUCTURE, "Invalid reference for an image: " + ire.getReferenceString());
            return null;
        } catch(OutOfMemoryError oom) {
            Logger.log(AndroidLogger.TYPE_ERROR_ASSERTION, "Out of memory loading reference: " + jrReference);
            return null;
        } catch(IOException uie){
            Logger.log(AndroidLogger.TYPE_ERROR_ASSERTION, "IO Exception loading reference: " + jrReference);
            return null;
        }
    }

    /**
     * Pass in a string representing either a GeoPont or an address and get back a valid
     * GeoURI that can be passed as an intent argument 
     */
    public static String getGeoIntentURI(String rawInput){
        try{
            GeoPointData mGeoPointData = new GeoPointData().cast(new UncastData(rawInput));
            String latitude = Double.toString(mGeoPointData.getValue()[0]);
            String longitude= Double.toString(mGeoPointData.getValue()[1]);
            return "geo:" + latitude + "," + longitude + "?q=" + latitude + "," + longitude;
            
        }catch(IllegalArgumentException iae){
            return "geo:0,0?q=" + rawInput;
        }
    }
	
	public static String stripArguments(String input){
		if(input.contains("{") && input.contains("}")){
			String replaced = input.substring(input.indexOf("{")-1, input.indexOf("}")+1);
			return input.replace(replaced, "").trim();
		}
		return input;
	}
}
