/**
 * 
 */
package org.commcare.android.javarosa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import org.commcare.android.database.SqlIndexedStorageUtility;
import org.javarosa.core.api.ILogger;
import org.javarosa.core.log.IFullLogSerializer;
import org.javarosa.core.log.LogEntry;
import org.javarosa.core.log.StreamLogSerializer;
import org.javarosa.core.services.storage.EntityFilter;
import org.javarosa.core.services.storage.StorageFullException;


/**
 * 
 * Logging engine for CommCare ODK Environments.
 * 
 * @author ctsims
 *
 */
public class AndroidLogger implements ILogger {
	
	//Log Types:
	/** Fatal problem with one of CommCare's cryptography libraries */
	public static final String TYPE_ERROR_CRYPTO = "error-crypto";
	
	/** Some invariant application assumption has been violated */
	public static final String TYPE_ERROR_ASSERTION = "error-state";
	
	/** Some invariant application assumption has been violated */
	public static final String TYPE_ERROR_WORKFLOW = "error-workflow";
	
	/** Something bad happened because of network connectivity **/
	public static final String TYPE_WARNING_NETWORK ="warning-network";

	/** Logs relating to user events (login/logout/restore, etc) **/
	public static final String TYPE_USER = "user";
	
	/** Logs relating to the external files and resources which make up an app **/
	public static final String TYPE_RESOURCES = "resources";

	/** Maintenance events (autopurging, cleanups, etc) **/
	public static final String TYPE_MAINTENANCE = "maintenance";
	
	/** Form Entry workflow messages **/
	public static final String TYPE_FORM_ENTRY = "form-entry";
	
	//TODO: Currently assumes that it gets back iterated records in RecordID order.
	//when serializing a limited number of records then clearing

	SqlIndexedStorageUtility<AndroidLogEntry> storage;
	
	private int lastEntry = -1;
	private boolean serializing = false;
	
	private final Object serializationLock = new Object();
	
	public AndroidLogger(SqlIndexedStorageUtility<AndroidLogEntry> storage) {
		this.storage = storage;
	}
	
	@Override
	public void log(String type, String message, Date logDate) {
		try {
			storage.write(new AndroidLogEntry(type, message, logDate));
		} catch (StorageFullException e) {
			e.printStackTrace();
			panic();
		}
	}

	@Override
	public void clearLogs() {
		if(serializing) {
			storage.removeAll();
		} else {
			storage.removeAll(new EntityFilter<AndroidLogEntry>() {
					@Override
					public boolean matches(AndroidLogEntry e) {
						if(e.getID() <= lastEntry) {
							return true;
						} else {
							return false;
						}
					}
					
				});
		
		}
	}

	@Override
	public <T> T serializeLogs(IFullLogSerializer<T> serializer) {
		ArrayList<LogEntry> logs = new ArrayList<LogEntry>(); 
		for(AndroidLogEntry entry : storage) {
			logs.add(entry);
			if(serializing) {
				if(entry.getID() > lastEntry) {
					lastEntry = entry.getID();
				}
			}
		}
		return serializer.serializeLogs(logs.toArray(new LogEntry[0]));
	}

	@Override
	public void serializeLogs(StreamLogSerializer serializer) throws IOException {
		for(AndroidLogEntry entry : storage) {
			serializer.serializeLog(entry.getID(), entry);
			if(serializing) {
				if(entry.getID() > lastEntry) {
					lastEntry = entry.getID();
				}
			}
		}
	}

	@Override
	public void serializeLogs(StreamLogSerializer serializer, int limit) throws IOException {
		int count = 0;
		for(AndroidLogEntry entry : storage) {
			serializer.serializeLog(entry.getID(), entry);
			if(serializing) {
				if(entry.getID() > lastEntry) {
					lastEntry = entry.getID();
				}
			}
			count++;
			if(count > limit) { break; }
		}
	}

	@Override
	public void panic() {
		//Unclear
	}

	@Override
	public int logSize() {
		return storage.getNumRecords();
	}

	@Override
	public void halt() {
		//Meh.
	}
	
	/**
	 * Call before serializing to limit what records will be purged during any 
	 * calls to clear records.
	 * 
	 * TODO: This is kind of weird.
	 */
	public void beginSerializationSession() {
		synchronized(serializationLock) {
			serializing = true;
			lastEntry = -1;
		}
	}
	
	/**
	 * Call after done with a serialization/purging session to reset the internal
	 * state of the logger 
	 *  
	 * TODO: This is kind of weird.
	 */
	public void endSerializatonSession() {
		synchronized(serializationLock) {
			serializing = false;
			lastEntry = -1;
		}
	}

}
