/*  Copyright (C) 2009 Mobile Sorcery AB

    This program is free software; you can redistribute it and/or modify it
    under the terms of the Eclipse Public License v1.0.

    This program is distributed in the hope that it will be useful, but WITHOUT
    ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
    FITNESS FOR A PARTICULAR PURPOSE. See the Eclipse Public License v1.0 for
    more details.

    You should have received a copy of the Eclipse Public License v1.0 along
    with this program. It is also available at http://www.eclipse.org/legal/epl-v10.html
*/
package com.mobilesorcery.sdk.core;

import java.io.InputStream;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.mobilesorcery.sdk.internal.ErrorPackager;
import com.mobilesorcery.sdk.internal.PID;
import com.mobilesorcery.sdk.internal.PROCESS;
import com.mobilesorcery.sdk.internal.PackagerProxy;
import com.mobilesorcery.sdk.internal.PropertyInitializerProxy;
import com.mobilesorcery.sdk.internal.RebuildListener;
import com.mobilesorcery.sdk.internal.ReindexListener;
import com.mobilesorcery.sdk.internal.debug.MoSyncBreakpointSynchronizer;
import com.mobilesorcery.sdk.internal.dependencies.DependencyManager;
import com.mobilesorcery.sdk.lib.JNALibInitializer;
import com.mobilesorcery.sdk.profiles.filter.IDeviceFilterFactory;
import com.mobilesorcery.sdk.profiles.filter.elementfactories.ConstantFilterFactory;
import com.mobilesorcery.sdk.profiles.filter.elementfactories.FeatureFilterFactory;
import com.mobilesorcery.sdk.profiles.filter.elementfactories.ProfileFilterFactory;
import com.mobilesorcery.sdk.profiles.filter.elementfactories.VendorFilterFactory;

/**
 * The activator class controls the plug-in life cycle
 */
public class CoreMoSyncPlugin extends AbstractUIPlugin {

    // The plug-in ID
    public static final String PLUGIN_ID = "com.mobilesorcery.sdk.core";

    // The shared instance
    private static CoreMoSyncPlugin plugin;

    private ArrayList<Pattern> patterns = new ArrayList<Pattern>();

    private ArrayList<IPackager> packagers = new ArrayList<IPackager>();

    private Map<String, Map<String, IPropertyInitializer>> propertyInitializers = new HashMap<String, Map<String, IPropertyInitializer>>();

	private DependencyManager<IProject> projectDependencyManager;

    private Properties panicMessages = new Properties();

    private ReindexListener reindexListener;

    private Integer[] sortedPanicErrorCodes;

	private MoSyncBreakpointSynchronizer bpSync;
	
	private boolean isHeadless = false;

	private HashMap<String, IDeviceFilterFactory> factories;

	private boolean updaterInitialized;

	private IUpdater updater;

	private IProvider<IProcessConsole, String> ideProcessConsoleProvider;

	private EmulatorProcessManager emulatorProcessManager;

    /**
     * The constructor
     */
    public CoreMoSyncPlugin() {
    }

    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        initReIndexerListener();
        initRebuildOnProfileChangeListener();
        initNativeLibs(context);
        initPackagers();
        initDeviceFilterFactories();
        initPanicErrorMessages();
        initPropertyInitializers();
        initGlobalDependencyManager();        
        initEmulatorProcessManager();
        installBreakpointHack();
		checkAutoUpdate();
    }

	/**
     * Returns whether this app is running in headless mode.
     * @return
     */
    public static boolean isHeadless() {
    	return plugin.isHeadless;
    }

    /**
     * Sets this app to headless/non-headless mode
     * @param isHeadless
     */
	public static void setHeadless(boolean isHeadless) {
		plugin.isHeadless = isHeadless;
	}

    private void installBreakpointHack() {    	
    	bpSync = new MoSyncBreakpointSynchronizer();
    	bpSync.install();
	}

	private void initGlobalDependencyManager() {
    	// Currently, all workspaces share this guy -- fixme later.
        this.projectDependencyManager = new DependencyManager<IProject>();
	}

	private void initRebuildOnProfileChangeListener() {
        MoSyncProject.addGlobalPropertyChangeListener(new RebuildListener());
    }

    public void stop(BundleContext context) throws Exception {
        plugin = null;
        projectDependencyManager = null;
        MoSyncProject.removeGlobalPropertyChangeListener(reindexListener);
        bpSync.uninstall();
        super.stop(context);
    }

    private void initPropertyInitializers() {
        IConfigurationElement[] elements = Platform.getExtensionRegistry().getConfigurationElementsFor(
                IPropertyInitializerDelegate.EXTENSION_POINT);
        propertyInitializers = new HashMap<String, Map<String, IPropertyInitializer>>();

        for (int i = 0; i < elements.length; i++) {
            String context = PropertyInitializerProxy.getContext(elements[i]);
            String prefix = PropertyInitializerProxy.getPrefix(elements[i]);

            if (context != null && prefix != null) {
                Map<String, IPropertyInitializer> prefixMap = propertyInitializers.get(context);
                if (prefixMap == null) {
                    prefixMap = new HashMap<String, IPropertyInitializer>();
                    propertyInitializers.put(context, prefixMap);
                }

                prefixMap.put(prefix, new PropertyInitializerProxy(elements[i]));
            }
        }
    }

    /**
     * <p>
     * From the registered <code>IPropertyInitializerDelegate</code>s, returns
     * the default value for <code>key</code>, where <code>key</code> has the
     * format <code>prefix:subkey</code>.
     * </p>
     * <p>
     * <code>IPropertyInitializerDelegate</code>s are always registered with
     * context and prefix, which are used for lookup.
     * </p>
     * <p>
     * The context is the same as is returned from the <code>getContext()</code>
     * method in <code>IPropertyOwner</code>.
     *
     * @param owner
     * @param key
     * @return May return <code>null</code>
     */
    public String getDefaultValue(IPropertyOwner owner, String key) {
        Map<String, IPropertyInitializer> prefixMap = propertyInitializers.get(owner.getContext());
        if (prefixMap != null) {
            String[] prefixAndSubkey = key.split(":", 2);
            if (prefixAndSubkey.length == 2) {
                IPropertyInitializer initializer = prefixMap.get(prefixAndSubkey[0]);
                return initializer.getDefaultValue(owner, key);
            }
        }

        return "";
    }

    private void initReIndexerListener() {
        reindexListener = new ReindexListener();
        MoSyncProject.addGlobalPropertyChangeListener(new ReindexListener());
    }

    private void initEmulatorProcessManager() {
    	this.emulatorProcessManager = new EmulatorProcessManager();
	}

    private void initPanicErrorMessages() {
        try {
            URL url = FileLocator.find(getBundle(), new Path("$nl$/panicmessages.properties"), new HashMap());
            InputStream messagesStream = url.openStream();
            panicMessages.load(messagesStream);
            messagesStream.close();
        } catch (Exception e) {
            // Just ignore.
            getLog().log(new Status(IStatus.WARNING, PLUGIN_ID, "Could not initialize panic messages", e));
        }

        TreeSet<Integer> result = new TreeSet<Integer>();
        for (Enumeration errorCodes = panicMessages.keys(); errorCodes.hasMoreElements(); ) {
            try {
                String errorCode = (String) errorCodes.nextElement();
                int errorCodeValue = Integer.parseInt(errorCode);
                result.add(errorCodeValue);
            } catch (Exception e) {
                // Just ignore.
            }
        }

        sortedPanicErrorCodes = result.toArray(new Integer[result.size()]);
    }


    /**
     * Returns the shared instance
     *
     * @return the shared instance
     */
    public static CoreMoSyncPlugin getDefault() {
        return plugin;
    }

    /**
     * Returns an image descriptor for the image file at the given plug-in
     * relative path
     *
     * @param path
     *            the path
     * @return the image descriptor
     */
    public static ImageDescriptor getImageDescriptor(String path) {
        return imageDescriptorFromPlugin(PLUGIN_ID, path);
    }

    public void log(Throwable e) {
        getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, e.getMessage(), e));
    }

    public void initNativeLibs(BundleContext context) {
        try {
            JNALibInitializer.init(this.getBundle(), new Path("pipelib.dll"));
            PROCESS dummy = PROCESS.INSTANCE; // Just to execute the .clinit.

            JNALibInitializer.init(this.getBundle(), new Path("pid2.dll"));

            if (isDebugging()) {
            	trace("Process id: " + getPid());
            }
        } catch (Throwable t) {
            log(t);
            t.printStackTrace();
        }
    }

    private void initPackagers() {
        IConfigurationElement[] elements = Platform.getExtensionRegistry().getConfigurationElementsFor(IPackager.EXTENSION_POINT);
        for (int i = 0; i < elements.length; i++) {
            String pattern = elements[i].getAttribute(PackagerProxy.PATTERN);
            Pattern platformPattern = Pattern.compile(pattern);
            patterns.add(platformPattern);
            packagers.add(new PackagerProxy(elements[i]));
        }
    }

    /**
     * Returns the packager for a specific platform.
     * @param platform
     * @return A non-null packager. If no packager is found,
     * a default <code>ErrorPackager</code> is returned
     * @see ErrorPackager
     */
    public IPackager getPackager(String platform) {
        for (int i = 0; i < patterns.size(); i++) {
            Pattern pattern = patterns.get(i);
            if (pattern.matcher(platform).matches()) {
                return packagers.get(i);
            }
        }

        return ErrorPackager.getDefault();
    }

	/**
     * Returns a sorted list of all panic error codes.
     * @return
     */
    public Integer[] getAllPanicErrorCodes() {
        return sortedPanicErrorCodes;
    }

    /**
     * Returns the panic message corresponding to <code>errcode</code>
     * @param errcode
     * @return
     */
    public String getPanicMessage(int errcode) {
        return panicMessages.getProperty(Integer.toString(errcode));
    }


    public DependencyManager<IProject> getProjectDependencyManager() {
    	return getProjectDependencyManager(ResourcesPlugin.getWorkspace());
    }

    public DependencyManager<IProject> getProjectDependencyManager(IWorkspace ws) {
    	return projectDependencyManager;
    }
    
    /**
     * Returns the Eclipse OS Process ID.
     * @return
     */
    public static String getPid() {
        return "" + PID.INSTANCE.pid();
    }

    public IProcessUtil getProcessUtil() {
    	return PROCESS.INSTANCE;
    }
    
    /**
     * <p>Outputs a trace message.</p>
     * <p>Please use this pattern:
     * <blockquote><code>
     *     if (CoreMoSyncPlugin.getDefault().isDebugging()) {
     *         trace("A trace message");
     *     }
     * </code></blockquote>
     * </p>
     * @param msg
     */
	public static void trace(Object msg) {
		System.out.println(msg);
	}

    /**
     * <p>Outputs a trace message.</p>
     * <p>The arguments match those of <code>MessageFormat.format</code>.</p>
     * @see {@link CoreMoSyncPlugin#trace(Object)};
     */
	public static void trace(String msg, Object... args) {
		trace(MessageFormat.format(msg, args));
	}

	private void initDeviceFilterFactories() {
		factories = new HashMap<String, IDeviceFilterFactory>();
		// We'll just add them explicitly
		factories.put(ConstantFilterFactory.ID, new ConstantFilterFactory());
		factories.put(VendorFilterFactory.ID, new VendorFilterFactory());
		factories.put(FeatureFilterFactory.ID, new FeatureFilterFactory());
		factories.put(ProfileFilterFactory.ID, new ProfileFilterFactory());
	}
	
	/**
	 * <p>Returns an <code>IDeviceFilterFactory</code>.</p>
	 * <p>Examples of <code>IDeviceFilterFactories</code> are
	 * <code>ConstantFilterFactory</code> and <code>VenderFilterFactory</code>. 
	 * @param factoryId
	 * @return
	 */
	public IDeviceFilterFactory getDeviceFilterFactory(String factoryId) {
		if (factoryId == null) {
			return null;			
		}
		
		// Kind of an IElementFactory, but without the UI deps.
		return factories.get(factoryId);
	}
	
	public IProcessConsole createConsole(String consoleName) {
		if (isHeadless) {
			return new LogProcessConsole(consoleName);
		} else {
			return ideProcessConsoleProvider == null ? new LogProcessConsole(consoleName) : ideProcessConsoleProvider.get(consoleName);
		}
	}
		
	public IUpdater getUpdater() {
		if (!updaterInitialized) {
			IExtensionRegistry registry = Platform.getExtensionRegistry();
			IConfigurationElement[] elements = registry
					.getConfigurationElementsFor("com.mobilesorcery.sdk.updater");
			if (elements.length > 0) {
				try {
					updater = (IUpdater) elements[0]
							.createExecutableExtension("implementation");
				} catch (CoreException e) {
					getLog().log(e.getStatus());
				}
			}
			
			updaterInitialized = true;
		}
		
		return updater;
	}

	private void checkAutoUpdate() {
		if (CoreMoSyncPlugin.isHeadless()) {
			return;
		}
		
		IUpdater updater = getUpdater();
		if (updater != null) {
			updater.update(false);
		}
	}

	/**
	 * <p>Returns the (single) emulator process manager</p>
	 * @return
	 */
	public EmulatorProcessManager getEmulatorProcessManager() {
		return emulatorProcessManager;
	}
	
	/**
	 * INTERNAL: Clients should not call this method.
	 */
	public void setIDEProcessConsoleProvider(IProvider<IProcessConsole, String> ideProcessConsoleProvider) {
		// I'm lazy - Instead of extension points...
		this.ideProcessConsoleProvider = ideProcessConsoleProvider;
	}
}