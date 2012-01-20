/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.launchpad.installer.impl;

import java.util.Dictionary;
import java.util.Hashtable;

import org.apache.sling.installer.api.OsgiInstaller;
import org.apache.sling.installer.api.event.InstallationListener;
import org.apache.sling.launchpad.api.LaunchpadContentProvider;
import org.apache.sling.launchpad.api.StartupHandler;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

/**
 * The <code>ServicesListener</code> listens for the required services
 * and starts/stops the scanners based on the availability of the
 * services.
 */
public class ServicesListener {

    /** The bundle context. */
    private final BundleContext bundleContext;

    /** The listener for the installer. */
    private final Listener installerListener;

    /** The listener for the provider. */
    private final Listener providerListener;

    /** The listener for the startup handler. */
    private final Listener startupListener;

    private ServiceRegistration launchpadListenerReg;

    private volatile boolean installed = false;

    public ServicesListener(final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        this.installerListener = new Listener(OsgiInstaller.class.getName());
        this.providerListener = new Listener(LaunchpadContentProvider.class.getName());
        this.startupListener = new Listener(StartupHandler.class.getName());
        this.installerListener.start();
        this.providerListener.start();
        this.startupListener.start();
    }

    public synchronized void notifyChange() {
        // check if all services are available
        final OsgiInstaller installer = (OsgiInstaller)this.installerListener.getService();
        final LaunchpadContentProvider lcp = (LaunchpadContentProvider)this.providerListener.getService();

        if ( installer != null && lcp != null ) {
            if ( !installed ) {
                installed = true;
                LaunchpadConfigInstaller.install(installer, lcp);
            }
        }
        final StartupHandler handler = (StartupHandler)this.startupListener.getService();
        if ( handler != null ) {
            if ( launchpadListenerReg == null ) {
                final LaunchpadListener launchpadListener = new LaunchpadListener(handler);
                final Dictionary<String, Object> props = new Hashtable<String, Object>();
                props.put(Constants.SERVICE_DESCRIPTION, "Apache Sling Launchpad Startup Listener");
                props.put(Constants.SERVICE_VENDOR, "The Apache Software Foundation");
                launchpadListenerReg = this.bundleContext.registerService(InstallationListener.class.getName(), launchpadListener, props);
            }
        } else {
            if ( launchpadListenerReg != null ) {
                launchpadListenerReg.unregister();
                launchpadListenerReg = null;
            }
        }
    }

    /**
     * Deactivate this listener.
     */
    public void deactivate() {
        this.installerListener.deactivate();
        this.providerListener.deactivate();
        this.startupListener.deactivate();
    }

    protected final class Listener implements ServiceListener {

        private final String serviceName;

        private ServiceReference reference;
        private Object service;

        public Listener(final String serviceName) {
            this.serviceName = serviceName;
        }

        public void start() {
            this.retainService();
            try {
                bundleContext.addServiceListener(this, "("
                        + Constants.OBJECTCLASS + "=" + serviceName + ")");
            } catch (final InvalidSyntaxException ise) {
                // this should really never happen
                throw new RuntimeException("Unexpected exception occured.", ise);
            }
        }

        public void deactivate() {
            bundleContext.removeServiceListener(this);
        }

        public synchronized Object getService() {
            return this.service;
        }
        private synchronized void retainService() {
            if ( this.reference == null ) {
                this.reference = bundleContext.getServiceReference(this.serviceName);
                if ( this.reference != null ) {
                    this.service = bundleContext.getService(this.reference);
                    if ( this.service == null ) {
                        this.reference = null;
                    } else {
                        notifyChange();
                    }
                }
            }
        }

        private synchronized void releaseService() {
            if ( this.reference != null ) {
                this.service = null;
                bundleContext.ungetService(this.reference);
                this.reference = null;
                notifyChange();
            }
        }

        /**
         * @see org.osgi.framework.ServiceListener#serviceChanged(org.osgi.framework.ServiceEvent)
         */
        public void serviceChanged(ServiceEvent event) {
            if (event.getType() == ServiceEvent.REGISTERED && this.service == null ) {
                this.retainService();
            } else if ( event.getType() == ServiceEvent.UNREGISTERING && this.service != null ) {
                this.releaseService();
            }
        }
    }
}
