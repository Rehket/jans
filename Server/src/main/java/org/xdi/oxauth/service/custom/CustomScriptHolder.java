/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.service.custom;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.IOUtils;
import org.jboss.seam.Component;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Logger;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Observer;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.async.Asynchronous;
import org.jboss.seam.async.TimerSchedule;
import org.jboss.seam.core.Events;
import org.jboss.seam.log.Log;
import org.python.core.PyLong;
import org.python.core.PyObject;
import org.xdi.exception.PythonException;
import org.xdi.model.SimpleCustomProperty;
import org.xdi.oxauth.model.util.Util;
import org.xdi.oxauth.service.custom.conf.CustomScript;
import org.xdi.oxauth.service.custom.conf.CustomScriptType;
import org.xdi.oxauth.service.custom.interfaces.BaseExternalType;
import org.xdi.oxauth.service.custom.model.CustomScriptConfiguration;
import org.xdi.service.PythonService;
import org.xdi.util.StringHelper;

/**
 * Provides actual versions of scrips
 *
 * @author Yuriy Movchan Date: 12/03/2014
 */
@Scope(ScopeType.APPLICATION)
@Name("customScriptHolder")
public class CustomScriptHolder implements Serializable {

	private static final long serialVersionUID = -4225890597520443390L;

	private final static String EVENT_TYPE = "CustomScriptHolderTimerEvent";
    private final static int DEFAULT_INTERVAL = 30; // 30 seconds
    
    private final static String[] CUSTOM_SCRIPT_CHECK_ATTRIBUTES = { "dn", "inum", "oxRevision", "gluuStatus" };

	@Logger
	private Log log;

	@In
	private PythonService pythonService;
	
	@In
	private CustomScriptService customScriptService;

	private List<CustomScriptType> supportedCustomScriptTypes;
	private Map<String, CustomScriptConfiguration> customScriptConfigurations;

	private AtomicBoolean isActive;
	private long lastFinishedTime;

	private Map<CustomScriptType, List<CustomScriptConfiguration>> customScriptConfigurationsByScriptType;

    public void init(List<CustomScriptType> supportedCustomScriptTypes) {
		this.supportedCustomScriptTypes = supportedCustomScriptTypes;

		this.isActive = new AtomicBoolean(false);
		this.lastFinishedTime = System.currentTimeMillis();

		reload();

		Events.instance().raiseTimedEvent(EVENT_TYPE, new TimerSchedule(1 * 60 * 1000L, DEFAULT_INTERVAL * 1000L));
    }

	@Observer(EVENT_TYPE)
	@Asynchronous
	public void reloadTimerEvent() {
		if (this.isActive.get()) {
			return;
		}

		if (!this.isActive.compareAndSet(false, true)) {
			return;
		}

		try {
			reload();
		} catch (Throwable ex) {
			log.error("Exception happened while reloading custom scripts configuration", ex);
		} finally {
			this.isActive.set(false);
			this.lastFinishedTime = System.currentTimeMillis();
			log.trace("Last finished time '{0}'", new Date(this.lastFinishedTime));
		}
	}

	// Commented out due to bug in SEAM
	//@Destroy
	public void destroy() {
		log.debug("Destroying custom scripts configurations");
		if (this.customScriptConfigurations == null) {
			return;
		}

		// Destroy authentication methods
		for (Entry<String, CustomScriptConfiguration> customScriptConfigurationEntry : this.customScriptConfigurations.entrySet()) {
			destroyCustomScript(customScriptConfigurationEntry.getValue());
		}
	}

	private void reload() {
		// Load current script revisions
		List<CustomScript> customScripts = customScriptService.findCustomScripts(supportedCustomScriptTypes, CUSTOM_SCRIPT_CHECK_ATTRIBUTES);

		// Store updated external authenticator configurations
		this.customScriptConfigurations = reloadCustomScriptConfigurations(this.customScriptConfigurations, customScripts);

		// Group external authenticator configurations by usage type
		this.customScriptConfigurationsByScriptType = groupCustomScriptConfigurationsByScriptType(this.customScriptConfigurations);
	}

	private Map<String, CustomScriptConfiguration> reloadCustomScriptConfigurations(
			Map<String,  CustomScriptConfiguration> customScriptConfigurations, List<CustomScript> newCustomScripts) {
		Map<String, CustomScriptConfiguration> newCustomScriptConfigurations;
		if (customScriptConfigurations == null) {
			newCustomScriptConfigurations = new HashMap<String, CustomScriptConfiguration>();
		} else {
			// Clone old map to avoid reload not changed scripts becuase it's time and CPU consuming process
			newCustomScriptConfigurations = new HashMap<String, CustomScriptConfiguration>(customScriptConfigurations);
		}

		List<String> newSupportedCustomScriptInums = new ArrayList<String>();
		for (CustomScript newCustomScript : newCustomScripts) {
	        if (!newCustomScript.isEnabled()) {
	        	continue;
	        }
	        	
			String newSupportedCustomScriptInum = StringHelper.toLowerCase(newCustomScript.getInum());
			newSupportedCustomScriptInums.add(newSupportedCustomScriptInum);

			CustomScriptConfiguration prevCustomScriptConfiguration = newCustomScriptConfigurations.get(newSupportedCustomScriptInum);
			if ((prevCustomScriptConfiguration == null) || (prevCustomScriptConfiguration.getCustomScript().getRevision() != newCustomScript.getRevision())) {
				// Destroy old version properly before creating new one
				if (prevCustomScriptConfiguration != null) {
					destroyCustomScript(prevCustomScriptConfiguration);
				}
				
				// Load script entry with all attributes
				CustomScript loadedCustomScript = customScriptService.getCustomScriptByDn(newCustomScript.getDn());

				// Prepare configuration attributes
				Map<String, SimpleCustomProperty> newConfigurationAttributes = new HashMap<String, SimpleCustomProperty>();
				for (SimpleCustomProperty simpleCustomProperty : loadedCustomScript.getProperties()) {
					newConfigurationAttributes.put(simpleCustomProperty.getValue1(), simpleCustomProperty);
				}

				// Create authenticator
	        	BaseExternalType newCustomScriptExternalType = createExternalType(loadedCustomScript, newConfigurationAttributes);

	        	CustomScriptConfiguration newCustomScriptConfiguration = new CustomScriptConfiguration(loadedCustomScript, newCustomScriptExternalType, newConfigurationAttributes);

				// Store configuration and authenticator
				newCustomScriptConfigurations.put(newSupportedCustomScriptInum, newCustomScriptConfiguration);
			}
		}

		// Remove old external authenticator configurations
		for (Iterator<Entry<String, CustomScriptConfiguration>> it = newCustomScriptConfigurations.entrySet().iterator(); it.hasNext();) {
			Entry<String, CustomScriptConfiguration> externalAuthenticatorConfigurationEntry = it.next();

			String prevSupportedCustomScriptInum = externalAuthenticatorConfigurationEntry.getKey();

			if (!newSupportedCustomScriptInums.contains(prevSupportedCustomScriptInum)) {
				// Destroy old authentication method
				destroyCustomScript(externalAuthenticatorConfigurationEntry.getValue());
				it.remove();
			}
		}

		return newCustomScriptConfigurations;
	}

	private boolean destroyCustomScript(CustomScriptConfiguration customScriptConfiguration) {
		String customScriptInum = customScriptConfiguration.getInum();

		boolean result = executeCustomScriptDestroy(customScriptConfiguration);
		if (!result) {
			log.error("Failed to destroy custom script '{0}' correctly", customScriptInum);
		}
		
		return result;
	}

	private Map<CustomScriptType, List<CustomScriptConfiguration>> groupCustomScriptConfigurationsByScriptType(Map<String, CustomScriptConfiguration> customScriptConfigurations) {
		Map<CustomScriptType, List<CustomScriptConfiguration>> newCustomScriptConfigurationsByScriptType = new HashMap<CustomScriptType, List<CustomScriptConfiguration>>();
		
		for (CustomScriptType customScriptType : this.supportedCustomScriptTypes) {
			List<CustomScriptConfiguration> customConfigurationsByScriptType = new ArrayList<CustomScriptConfiguration>();
			newCustomScriptConfigurationsByScriptType.put(customScriptType, customConfigurationsByScriptType);
		}

		for (CustomScriptConfiguration customScriptConfiguration : customScriptConfigurations.values()) {
			CustomScriptType customScriptType = customScriptConfiguration.getCustomScript().getScriptType();
			List<CustomScriptConfiguration> customConfigurationsByScriptType = newCustomScriptConfigurationsByScriptType.get(customScriptType);
			customConfigurationsByScriptType.add(customScriptConfiguration);
		}
		
		return newCustomScriptConfigurationsByScriptType;
	}

	private BaseExternalType createExternalType(CustomScript customScript, Map<String, SimpleCustomProperty> configurationAttributes) {
		String customScriptInum = customScript.getInum();

		BaseExternalType externalType;
		try {
			externalType = createExternalTypeFromStringWithPythonException(customScript, configurationAttributes);
		} catch (PythonException ex) {
			log.error("Failed to prepare external type '{0}'", ex, customScriptInum);
			return null;
		}

		if (externalType == null) {
			log.debug("Using default external type class");
			externalType = customScript.getScriptType().getDefaultImplementation();
		}

		return externalType;
	}

	public BaseExternalType createExternalTypeFromStringWithPythonException(CustomScript customScript, Map<String, SimpleCustomProperty> configurationAttributes) throws PythonException {
		String script = customScript.getScript();
		if (script == null) {
			return null;
		}

		CustomScriptType customScriptType = customScript.getScriptType();
		BaseExternalType externalType = null;

		InputStream bis = null;
		try {
            bis = new ByteArrayInputStream(script.getBytes(Util.UTF8_STRING_ENCODING));
            externalType = pythonService.loadPythonScript(bis, customScriptType.getPythonClass(),
            		customScriptType.getCustomScriptType(), new PyObject[] { new PyLong(System.currentTimeMillis()) });
		} catch (UnsupportedEncodingException e) {
            log.error(e.getMessage(), e);
        } finally {
			IOUtils.closeQuietly(bis);
		}

		if (externalType == null) {
			return null;
		}

		boolean initialized = false;
		try {
			initialized = externalType.init(configurationAttributes);
		} catch (Exception ex) {
            log.error("Failed to initialize custom script", ex);
		}

		if (initialized) {
			return externalType;
		}
		
		return null;
	}

	public boolean executeCustomScriptDestroy(CustomScriptConfiguration customScriptConfiguration) {
		try {
			log.debug("Executing python 'destroy' custom script method");
			BaseExternalType externalType = customScriptConfiguration.getExternalType();
			Map<String, SimpleCustomProperty> configurationAttributes = customScriptConfiguration.getConfigurationAttributes();
			return externalType.destroy(configurationAttributes);
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
		}

		return false;
	}

	public CustomScriptConfiguration getCustomScriptConfigurationByInum(String inum) {
		return this.customScriptConfigurations.get(inum);
	}

	public List<CustomScriptConfiguration> getCustomScriptConfigurationsByScriptType(CustomScriptType customScriptType) {
		return new ArrayList<CustomScriptConfiguration>(this.customScriptConfigurationsByScriptType.get(customScriptType));
	}

	public List<CustomScriptConfiguration> getCustomScriptConfigurations() {
		return new ArrayList<CustomScriptConfiguration>(this.customScriptConfigurations.values());
	}

    public static CustomScriptHolder instance() {
        return (CustomScriptHolder) Component.getInstance(CustomScriptHolder.class);
    }

}
