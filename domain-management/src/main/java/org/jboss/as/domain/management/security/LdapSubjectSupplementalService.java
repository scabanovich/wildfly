/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.domain.management.security;

import java.io.IOException;
import java.security.Principal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.security.auth.Subject;

import org.jboss.as.core.security.RealmGroup;
import org.jboss.as.core.security.RealmUser;
import org.jboss.as.domain.management.SecurityRealm;
import org.jboss.as.domain.management.connections.ConnectionManager;
import org.jboss.as.domain.management.security.BaseLdapGroupSearchResource.GroupName;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;

/**
 * A {@link SubjectSupplemental} for loading a users groups from LDAP.
 *
 * @author <a href="mailto:flemming.harms@gmail.com">Flemming Harms</a>
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class LdapSubjectSupplementalService implements Service<SubjectSupplementalService>, SubjectSupplementalService {

    private final InjectedValue<ConnectionManager> connectionManager = new InjectedValue<ConnectionManager>();
    private final InjectedValue<LdapUserSearcher> userSearcherInjector = new InjectedValue<LdapUserSearcher>();
    private final InjectedValue<LdapGroupSearcher> groupSearcherInjector = new InjectedValue<LdapGroupSearcher>();

    private LdapUserSearcher userSearcher;
    private LdapGroupSearcher groupSearcher;

    protected final int searchTimeLimit = 10000; // TODO - Maybe make configurable.

    private final String realmName;
    private final boolean shareConnection;
    private final boolean forceUserDnSearch;
    private final boolean iterative;
    private final GroupName groupName;

    public LdapSubjectSupplementalService(final String realmName, final boolean shareConnection, final boolean forceUserDnSearch, final boolean iterative, final GroupName groupName) {
        this.realmName = realmName;
        this.shareConnection = shareConnection;
        this.forceUserDnSearch = forceUserDnSearch;
        this.iterative = iterative;
        this.groupName = groupName;
    }

    /*
     * Service Methods
     */

    public SubjectSupplementalService getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }

    public void start(StartContext context) throws StartException {
        userSearcher = userSearcherInjector.getOptionalValue();
        groupSearcher = groupSearcherInjector.getValue();
    }

    public void stop(StopContext context) {

    }

    /*
     *  Access to Injectors
     */
    public Injector<ConnectionManager> getConnectionManagerInjector() {
        return connectionManager;
    }

    public Injector<LdapUserSearcher> getLdapUserSearcherInjector() {
        return userSearcherInjector;
    }

    public Injector<LdapGroupSearcher> getLdapGroupSearcherInjector() {
        return groupSearcherInjector;
    }

    /*
     * SubjectSupplementalService Method
     */

    public SubjectSupplemental getSubjectSupplemental(Map<String, Object> sharedState) {
        return new LdapSubjectSupplemental(sharedState);
    }

    /*
     * SubjectSupplementalMethods
     */

    public class LdapSubjectSupplemental implements SubjectSupplemental {

        private final Set<LdapEntry> searchedPerformed = new HashSet<LdapEntry>();
        private final Map<String, Object> sharedState;

        private DirContext dirContext = null;

        protected LdapSubjectSupplemental(final Map<String, Object> sharedState) {
            this.sharedState = sharedState;
        }

        /**
         * @see org.jboss.as.domain.management.security.SubjectSupplemental#supplementSubject(javax.security.auth.Subject)
         */
        public void supplementSubject(Subject subject) throws IOException {
            Set<RealmUser> users = subject.getPrincipals(RealmUser.class);
            Set<Principal> principals = subject.getPrincipals();

            try {
                dirContext = getSearchContext();

                // In general we expect exactly one RealmUser, however we could cope with multiple
                // identities so load the groups for them all.
                for (RealmUser current : users) {
                    principals.addAll(loadGroups(current));
                }

            } catch (Exception e) {
                if (e instanceof IOException) {
                    throw (IOException) e;
                }
                throw new IOException(e);
            } finally {
                safeClose(dirContext);
                dirContext = null;
            }
        }

        private Set<RealmGroup> loadGroups(RealmUser user) throws IOException, NamingException {
            LdapEntry entry = null;
            if (forceUserDnSearch == false && sharedState.containsKey(LdapEntry.class.getName())) {
                entry = (LdapEntry) sharedState.get(LdapEntry.class.getName());
            }
            if (entry == null || user.getName().equals(entry.getSimpleName())==false) {
                entry = userSearcher.userSearch(dirContext, user.getName());
            }

            return loadGroups(entry);
        }

        private Set<RealmGroup> loadGroups(LdapEntry entry) throws IOException, NamingException {
            Set<RealmGroup> realmGroups = new HashSet<RealmGroup>();

            Stack<LdapEntry[]> entries = new Stack<LdapEntry[]>();
            entries.push(loadGroupEntries(entry));
            while (entries.isEmpty() == false) {
                LdapEntry[] found = entries.pop();
                for (LdapEntry current : found) {
                    realmGroups.add(new RealmGroup(realmName, groupName == GroupName.SIMPLE ? current.getSimpleName() : current
                            .getDistinguishedName()));
                    if (iterative) {
                     entries.push(loadGroupEntries(current));
                    }
                }
            }

            return realmGroups;
        }

        private LdapEntry[] loadGroupEntries(LdapEntry entry) throws IOException, NamingException {
            if (searchedPerformed.add(entry)==false) {
                return new LdapEntry[0];
            }

            return groupSearcher.groupSearch(dirContext, entry);
        }

        private DirContext getSearchContext() throws Exception {
            DirContext searchContext;
            if (shareConnection && sharedState.containsKey(DirContext.class.getName())) {
                searchContext = (DirContext) sharedState.remove(DirContext.class.getName());
            } else {
                ConnectionManager connectionManager = LdapSubjectSupplementalService.this.connectionManager.getValue();
                searchContext = (DirContext) connectionManager.getConnection();
            }
            return searchContext;
        }

    }

    private void safeClose(Context context) {
        if (context != null) {
            try {
                context.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static final class ServiceUtil {

        private static final String SERVICE_SUFFIX = "ldap-authorization";

        private ServiceUtil() {
        }

        public static ServiceName createServiceName(final String realmName) {
            return SecurityRealm.ServiceUtil.createServiceName(realmName).append(SERVICE_SUFFIX);
        }

    }


}
