/*
 * The MIT License
 *
 * Copyright (c) 2010, Kohsuke Kawaguchi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.kohsuke.github;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker.Std;
import com.infradna.tool.bridge_method_injector.WithBridgeMethods;
import org.apache.commons.codec.Charsets;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.util.logging.Level.FINE;
import static org.kohsuke.github.Previews.INERTIA;
import static org.kohsuke.github.Previews.MACHINE_MAN;

/**
 * Root of the GitHub API.
 *
 * <h2>Thread safety</h2>
 * <p>
 * This library aims to be safe for use by multiple threads concurrently, although the library itself makes no attempt
 * to control/serialize potentially conflicting operations to GitHub, such as updating &amp; deleting a repository at
 * the same time.
 *
 * @author Kohsuke Kawaguchi
 */
public class GitHub {
    final String login;

    /**
     * Value of the authorization header to be sent with the request.
     */
    final String encodedAuthorization;

    private final ConcurrentMap<String, GHUser> users;
    private final ConcurrentMap<String, GHOrganization> orgs;
    // Cache of myself object.
    private GHMyself myself;
    private final String apiUrl;

    final RateLimitHandler rateLimitHandler;
    final AbuseLimitHandler abuseLimitHandler;

    private HttpConnector connector = HttpConnector.DEFAULT;

    private final Object headerRateLimitLock = new Object();
    private GHRateLimit headerRateLimit = null;
    private volatile GHRateLimit rateLimit = null;

    /**
     * Creates a client API root object.
     *
     * <p>
     * Several different combinations of the login/oauthAccessToken/password parameters are allowed to represent
     * different ways of authentication.
     *
     * <dl>
     * <dt>Log in anonymously
     * <dd>Leave all three parameters null and you will be making HTTP requests without any authentication.
     *
     * <dt>Log in with password
     * <dd>Specify the login and password, then leave oauthAccessToken null. This will use the HTTP BASIC auth with the
     * GitHub API.
     *
     * <dt>Log in with OAuth token
     * <dd>Specify oauthAccessToken, and optionally specify the login. Leave password null. This will send OAuth token
     * to the GitHub API. If the login parameter is null, The constructor makes an API call to figure out the user name
     * that owns the token.
     *
     * <dt>Log in with JWT token
     * <dd>Specify jwtToken. Leave password null. This will send JWT token to the GitHub API via the Authorization HTTP
     * header. Please note that only operations in which permissions have been previously configured and accepted during
     * the GitHub App will be executed successfully.
     * </dl>
     *
     * @param apiUrl
     *            The URL of GitHub (or GitHub enterprise) API endpoint, such as "https://api.github.com" or
     *            "http://ghe.acme.com/api/v3". Note that GitHub Enterprise has <code>/api/v3</code> in the URL. For
     *            historical reasons, this parameter still accepts the bare domain name, but that's considered
     *            deprecated. Password is also considered deprecated as it is no longer required for api usage.
     * @param login
     *            The user ID on GitHub that you are logging in as. Can be omitted if the OAuth token is provided or if
     *            logging in anonymously. Specifying this would save one API call.
     * @param oauthAccessToken
     *            Secret OAuth token.
     * @param password
     *            User's password. Always used in conjunction with the {@code login} parameter
     * @param connector
     *            HttpConnector to use. Pass null to use default connector.
     */
    GitHub(String apiUrl,
            String login,
            String oauthAccessToken,
            String jwtToken,
            String password,
            HttpConnector connector,
            RateLimitHandler rateLimitHandler,
            AbuseLimitHandler abuseLimitHandler) throws IOException {
        if (apiUrl.endsWith("/"))
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1); // normalize
        this.apiUrl = apiUrl;
        if (null != connector)
            this.connector = connector;

        if (oauthAccessToken != null) {
            encodedAuthorization = "token " + oauthAccessToken;
        } else {
            if (jwtToken != null) {
                encodedAuthorization = "Bearer " + jwtToken;
            } else if (password != null) {
                String authorization = (login + ':' + password);
                String charsetName = Charsets.UTF_8.name();
                encodedAuthorization = "Basic "
                        + new String(Base64.encodeBase64(authorization.getBytes(charsetName)), charsetName);
            } else {// anonymous access
                encodedAuthorization = null;
            }
        }

        users = new ConcurrentHashMap<String, GHUser>();
        orgs = new ConcurrentHashMap<String, GHOrganization>();
        this.rateLimitHandler = rateLimitHandler;
        this.abuseLimitHandler = abuseLimitHandler;

        if (login == null && encodedAuthorization != null && jwtToken == null)
            login = getMyself().getLogin();
        this.login = login;
    }

    /**
     * Obtains the credential from "~/.github" or from the System Environment Properties.
     *
     * @return the git hub
     * @throws IOException
     *             the io exception
     */
    public static GitHub connect() throws IOException {
        return GitHubBuilder.fromCredentials().build();
    }

    /**
     * Version that connects to GitHub Enterprise.
     *
     * @param apiUrl
     *            the api url
     * @param oauthAccessToken
     *            the oauth access token
     * @return the git hub
     * @throws IOException
     *             the io exception
     * @deprecated Use {@link #connectToEnterpriseWithOAuth(String, String, String)}
     */
    @Deprecated
    public static GitHub connectToEnterprise(String apiUrl, String oauthAccessToken) throws IOException {
        return connectToEnterpriseWithOAuth(apiUrl, null, oauthAccessToken);
    }

    /**
     * Version that connects to GitHub Enterprise.
     *
     * @param apiUrl
     *            The URL of GitHub (or GitHub Enterprise) API endpoint, such as "https://api.github.com" or
     *            "http://ghe.acme.com/api/v3". Note that GitHub Enterprise has <code>/api/v3</code> in the URL. For
     *            historical reasons, this parameter still accepts the bare domain name, but that's considered
     *            deprecated.
     * @param login
     *            the login
     * @param oauthAccessToken
     *            the oauth access token
     * @return the git hub
     * @throws IOException
     *             the io exception
     */
    public static GitHub connectToEnterpriseWithOAuth(String apiUrl, String login, String oauthAccessToken)
            throws IOException {
        return new GitHubBuilder().withEndpoint(apiUrl).withOAuthToken(oauthAccessToken, login).build();
    }

    /**
     * Version that connects to GitHub Enterprise.
     *
     * @param apiUrl
     *            the api url
     * @param login
     *            the login
     * @param password
     *            the password
     * @return the git hub
     * @throws IOException
     *             the io exception
     * @deprecated Use with caution. Login with password is not a preferred method.
     */
    @Deprecated
    public static GitHub connectToEnterprise(String apiUrl, String login, String password) throws IOException {
        return new GitHubBuilder().withEndpoint(apiUrl).withPassword(login, password).build();
    }

    /**
     * Connect git hub.
     *
     * @param login
     *            the login
     * @param oauthAccessToken
     *            the oauth access token
     * @return the git hub
     * @throws IOException
     *             the io exception
     */
    public static GitHub connect(String login, String oauthAccessToken) throws IOException {
        return new GitHubBuilder().withOAuthToken(oauthAccessToken, login).build();
    }

    /**
     * Connect git hub.
     *
     * @param login
     *            the login
     * @param oauthAccessToken
     *            the oauth access token
     * @param password
     *            the password
     * @return the git hub
     * @throws IOException
     *             the io exception
     * @deprecated Either OAuth token or password is sufficient, so there's no point in passing both. Use
     *             {@link #connectUsingPassword(String, String)} or {@link #connectUsingOAuth(String)}.
     */
    @Deprecated
    public static GitHub connect(String login, String oauthAccessToken, String password) throws IOException {
        return new GitHubBuilder().withOAuthToken(oauthAccessToken, login).withPassword(login, password).build();
    }

    /**
     * Connect using password git hub.
     *
     * @param login
     *            the login
     * @param password
     *            the password
     * @return the git hub
     * @throws IOException
     *             the io exception
     */
    public static GitHub connectUsingPassword(String login, String password) throws IOException {
        return new GitHubBuilder().withPassword(login, password).build();
    }

    /**
     * Connect using o auth git hub.
     *
     * @param oauthAccessToken
     *            the oauth access token
     * @return the git hub
     * @throws IOException
     *             the io exception
     */
    public static GitHub connectUsingOAuth(String oauthAccessToken) throws IOException {
        return new GitHubBuilder().withOAuthToken(oauthAccessToken).build();
    }

    /**
     * Connect using o auth git hub.
     *
     * @param githubServer
     *            the github server
     * @param oauthAccessToken
     *            the oauth access token
     * @return the git hub
     * @throws IOException
     *             the io exception
     */
    public static GitHub connectUsingOAuth(String githubServer, String oauthAccessToken) throws IOException {
        return new GitHubBuilder().withEndpoint(githubServer).withOAuthToken(oauthAccessToken).build();
    }

    /**
     * Connects to GitHub anonymously.
     * <p>
     * All operations that require authentication will fail.
     *
     * @return the git hub
     * @throws IOException
     *             the io exception
     */
    public static GitHub connectAnonymously() throws IOException {
        return new GitHubBuilder().build();
    }

    /**
     * Connects to GitHub Enterprise anonymously.
     * <p>
     * All operations that require authentication will fail.
     *
     * @param apiUrl
     *            the api url
     * @return the git hub
     * @throws IOException
     *             the io exception
     */
    public static GitHub connectToEnterpriseAnonymously(String apiUrl) throws IOException {
        return new GitHubBuilder().withEndpoint(apiUrl).build();
    }

    /**
     * An offline-only {@link GitHub} useful for parsing event notification from an unknown source.
     * <p>
     * All operations that require a connection will fail.
     *
     * @return An offline-only {@link GitHub}.
     */
    public static GitHub offline() {
        try {
            return new GitHubBuilder().withEndpoint("https://api.github.invalid")
                    .withConnector(HttpConnector.OFFLINE)
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("The offline implementation constructor should not connect", e);
        }
    }

    /**
     * Is this an anonymous connection
     *
     * @return {@code true} if operations that require authentication will fail.
     */
    public boolean isAnonymous() {
        return login == null && encodedAuthorization == null;
    }

    /**
     * Is this an always offline "connection".
     *
     * @return {@code true} if this is an always offline "connection".
     */
    public boolean isOffline() {
        return connector == HttpConnector.OFFLINE;
    }

    /**
     * Gets connector.
     *
     * @return the connector
     */
    public HttpConnector getConnector() {
        return connector;
    }

    /**
     * Gets api url.
     *
     * @return the api url
     */
    public String getApiUrl() {
        return apiUrl;
    }

    /**
     * Sets the custom connector used to make requests to GitHub.
     *
     * @param connector
     *            the connector
     */
    public void setConnector(HttpConnector connector) {
        this.connector = connector;
    }

    void requireCredential() {
        if (isAnonymous())
            throw new IllegalStateException(
                    "This operation requires a credential but none is given to the GitHub constructor");
    }

    URL getApiURL(String tailApiUrl) throws IOException {
        if (tailApiUrl.startsWith("/")) {
            if ("github.com".equals(apiUrl)) {// backward compatibility
                return new URL(GITHUB_URL + tailApiUrl);
            } else {
                return new URL(apiUrl + tailApiUrl);
            }
        } else {
            return new URL(tailApiUrl);
        }
    }

    Requester retrieve() {
        return new Requester(this).method("GET");
    }

    /**
     * Gets the current rate limit.
     *
     * @return the rate limit
     * @throws IOException
     *             the io exception
     */
    public GHRateLimit getRateLimit() throws IOException {
        GHRateLimit rateLimit;
        try {
            rateLimit = retrieve().to("/rate_limit", JsonRateLimit.class).resources;
        } catch (FileNotFoundException e) {
            // GitHub Enterprise doesn't have the rate limit
            // return a default rate limit that
            rateLimit = GHRateLimit.Unknown();
        }

        return this.rateLimit = rateLimit;
    }

    /**
     * Update the Rate Limit with the latest info from response header. Due to multi-threading requests might complete
     * out of order, we want to pick the one with the most recent info from the server.
     *
     * @param observed
     *            {@link GHRateLimit.Record} constructed from the response header information
     */
    void updateCoreRateLimit(@Nonnull GHRateLimit.Record observed) {
        synchronized (headerRateLimitLock) {
            if (headerRateLimit == null || shouldReplace(observed, headerRateLimit.getCore())) {
                headerRateLimit = GHRateLimit.fromHeaderRecord(observed);
                LOGGER.log(FINE, "Rate limit now: {0}", headerRateLimit);
            }
        }
    }

    /**
     * Update the Rate Limit with the latest info from response header. Due to multi-threading requests might complete
     * out of order, we want to pick the one with the most recent info from the server. Header date is only accurate to
     * the second, so we look at the information in the record itself.
     *
     * {@link GHRateLimit.UnknownLimitRecord}s are always replaced by regular {@link GHRateLimit.Record}s. Regular
     * {@link GHRateLimit.Record}s are never replaced by {@link GHRateLimit.UnknownLimitRecord}s. Candidates with
     * resetEpochSeconds later than current record are more recent. Candidates with the same reset and a lower remaining
     * count are more recent. Candidates with an earlier reset are older.
     *
     * @param candidate
     *            {@link GHRateLimit.Record} constructed from the response header information
     * @param current
     *            the current {@link GHRateLimit.Record} record
     */
    static boolean shouldReplace(@Nonnull GHRateLimit.Record candidate, @Nonnull GHRateLimit.Record current) {
        if (candidate instanceof GHRateLimit.UnknownLimitRecord
                && !(current instanceof GHRateLimit.UnknownLimitRecord)) {
            // Unknown candidate never replaces a regular record
            return false;
        } else if (current instanceof GHRateLimit.UnknownLimitRecord
                && !(candidate instanceof GHRateLimit.UnknownLimitRecord)) {
            // Any real record should replace an unknown Record.
            return true;
        } else {
            // records of the same type compare to each other as normal.
            return current.getResetEpochSeconds() < candidate.getResetEpochSeconds()
                    || (current.getResetEpochSeconds() == candidate.getResetEpochSeconds()
                            && current.getRemaining() > candidate.getRemaining());
        }
    }

    /**
     * Returns the most recently observed rate limit data or {@code null} if either there is no rate limit (for example
     * GitHub Enterprise) or if no requests have been made.
     *
     * @return the most recently observed rate limit data or {@code null}.
     */
    @CheckForNull
    public GHRateLimit lastRateLimit() {
        synchronized (headerRateLimitLock) {
            return headerRateLimit;
        }
    }

    /**
     * Gets the current rate limit while trying not to actually make any remote requests unless absolutely necessary.
     *
     * @return the current rate limit data.
     * @throws IOException
     *             if we couldn't get the current rate limit data.
     */
    @Nonnull
    public GHRateLimit rateLimit() throws IOException {
        synchronized (headerRateLimitLock) {
            if (headerRateLimit != null && !headerRateLimit.isExpired()) {
                return headerRateLimit;
            }
        }
        GHRateLimit rateLimit = this.rateLimit;
        if (rateLimit == null || rateLimit.isExpired()) {
            rateLimit = getRateLimit();
        }
        return rateLimit;
    }

    /**
     * Gets the {@link GHUser} that represents yourself.
     *
     * @return the myself
     * @throws IOException
     *             the io exception
     */
    @WithBridgeMethods(GHUser.class)
    public GHMyself getMyself() throws IOException {
        requireCredential();
        synchronized (this) {
            if (this.myself != null)
                return myself;

            GHMyself u = retrieve().to("/user", GHMyself.class);

            u.root = this;
            this.myself = u;
            return u;
        }
    }

    /**
     * Obtains the object that represents the named user.
     *
     * @param login
     *            the login
     * @return the user
     * @throws IOException
     *             the io exception
     */
    public GHUser getUser(String login) throws IOException {
        GHUser u = users.get(login);
        if (u == null) {
            u = retrieve().to("/users/" + login, GHUser.class);
            u.root = this;
            users.put(u.getLogin(), u);
        }
        return u;
    }

    /**
     * clears all cached data in order for external changes (modifications and del) to be reflected
     */
    public void refreshCache() {
        users.clear();
        orgs.clear();
    }

    /**
     * Interns the given {@link GHUser}.
     *
     * @param orig
     *            the orig
     * @return the user
     */
    protected GHUser getUser(GHUser orig) {
        GHUser u = users.get(orig.getLogin());
        if (u == null) {
            orig.root = this;
            users.put(orig.getLogin(), orig);
            return orig;
        }
        return u;
    }

    /**
     * Gets {@link GHOrganization} specified by name.
     *
     * @param name
     *            the name
     * @return the organization
     * @throws IOException
     *             the io exception
     */
    public GHOrganization getOrganization(String name) throws IOException {
        GHOrganization o = orgs.get(name);
        if (o == null) {
            o = retrieve().to("/orgs/" + name, GHOrganization.class).wrapUp(this);
            orgs.put(name, o);
        }
        return o;
    }

    /**
     * Gets a list of all organizations.
     *
     * @return the paged iterable
     */
    public PagedIterable<GHOrganization> listOrganizations() {
        return listOrganizations(null);
    }

    /**
     * Gets a list of all organizations starting after the organization identifier specified by 'since'.
     *
     * @param since
     *            the since
     * @return the paged iterable
     * @see <a href="https://developer.github.com/v3/orgs/#parameters">List All Orgs - Parameters</a>
     */
    public PagedIterable<GHOrganization> listOrganizations(final String since) {
        return retrieve().with("since", since)
                .asPagedIterable("/organizations", GHOrganization[].class, item -> item.wrapUp(GitHub.this));
    }

    /**
     * Gets the repository object from 'user/reponame' string that GitHub calls as "repository name"
     *
     * @param name
     *            the name
     * @return the repository
     * @throws IOException
     *             the io exception
     * @see GHRepository#getName() GHRepository#getName()
     */
    public GHRepository getRepository(String name) throws IOException {
        String[] tokens = name.split("/");
        return retrieve().to("/repos/" + tokens[0] + '/' + tokens[1], GHRepository.class).wrap(this);
    }

    /**
     * Gets the repository object from its ID
     *
     * @param id
     *            the id
     * @return the repository by id
     * @throws IOException
     *             the io exception
     */
    public GHRepository getRepositoryById(String id) throws IOException {
        return retrieve().to("/repositories/" + id, GHRepository.class).wrap(this);
    }

    /**
     * Returns a list of popular open source licenses
     *
     * @return a list of popular open source licenses
     * @throws IOException
     *             the io exception
     * @see <a href="https://developer.github.com/v3/licenses/">GitHub API - Licenses</a>
     */
    public PagedIterable<GHLicense> listLicenses() throws IOException {
        return retrieve().asPagedIterable("/licenses", GHLicense[].class, item -> item.wrap(GitHub.this));
    }

    /**
     * Returns a list of all users.
     *
     * @return the paged iterable
     * @throws IOException
     *             the io exception
     */
    public PagedIterable<GHUser> listUsers() throws IOException {
        return retrieve().asPagedIterable("/users", GHUser[].class, item -> item.wrapUp(GitHub.this));
    }

    /**
     * Returns the full details for a license
     *
     * @param key
     *            The license key provided from the API
     * @return The license details
     * @throws IOException
     *             the io exception
     * @see GHLicense#getKey() GHLicense#getKey()
     */
    public GHLicense getLicense(String key) throws IOException {
        return retrieve().to("/licenses/" + key, GHLicense.class);
    }

    /**
     * Gets complete list of open invitations for current user.
     *
     * @return the my invitations
     * @throws IOException
     *             the io exception
     */
    public List<GHInvitation> getMyInvitations() throws IOException {
        GHInvitation[] invitations = retrieve().to("/user/repository_invitations", GHInvitation[].class);
        for (GHInvitation i : invitations) {
            i.wrapUp(this);
        }
        return Arrays.asList(invitations);
    }

    /**
     * This method returns shallowly populated organizations.
     * <p>
     * To retrieve full organization details, you need to call {@link #getOrganization(String)} TODO: make this
     * automatic.
     *
     * @return the my organizations
     * @throws IOException
     *             the io exception
     */
    public Map<String, GHOrganization> getMyOrganizations() throws IOException {
        GHOrganization[] orgs = retrieve().to("/user/orgs", GHOrganization[].class);
        Map<String, GHOrganization> r = new HashMap<String, GHOrganization>();
        for (GHOrganization o : orgs) {
            // don't put 'o' into orgs because they are shallow
            r.put(o.getLogin(), o.wrapUp(this));
        }
        return r;
    }

    /**
     * Alias for {@link #getUserPublicOrganizations(String)}.
     *
     * @param user
     *            the user
     * @return the user public organizations
     * @throws IOException
     *             the io exception
     */
    public Map<String, GHOrganization> getUserPublicOrganizations(GHUser user) throws IOException {
        return getUserPublicOrganizations(user.getLogin());
    }

    /**
     * This method returns a shallowly populated organizations.
     * <p>
     * To retrieve full organization details, you need to call {@link #getOrganization(String)}
     *
     * @param login
     *            the user to retrieve public Organization membership information for
     * @return the public Organization memberships for the user
     * @throws IOException
     *             the io exception
     */
    public Map<String, GHOrganization> getUserPublicOrganizations(String login) throws IOException {
        GHOrganization[] orgs = retrieve().to("/users/" + login + "/orgs", GHOrganization[].class);
        Map<String, GHOrganization> r = new HashMap<String, GHOrganization>();
        for (GHOrganization o : orgs) {
            // don't put 'o' into orgs because they are shallow
            r.put(o.getLogin(), o.wrapUp(this));
        }
        return r;
    }

    /**
     * Gets complete map of organizations/teams that current user belongs to.
     * <p>
     * Leverages the new GitHub API /user/teams made available recently to get in a single call the complete set of
     * organizations, teams and permissions in a single call.
     *
     * @return the my teams
     * @throws IOException
     *             the io exception
     */
    public Map<String, Set<GHTeam>> getMyTeams() throws IOException {
        Map<String, Set<GHTeam>> allMyTeams = new HashMap<String, Set<GHTeam>>();
        for (GHTeam team : retrieve().to("/user/teams", GHTeam[].class)) {
            team.wrapUp(this);
            String orgLogin = team.getOrganization().getLogin();
            Set<GHTeam> teamsPerOrg = allMyTeams.get(orgLogin);
            if (teamsPerOrg == null) {
                teamsPerOrg = new HashSet<GHTeam>();
            }
            teamsPerOrg.add(team);
            allMyTeams.put(orgLogin, teamsPerOrg);
        }
        return allMyTeams;
    }

    /**
     * Gets a sigle team by ID.
     *
     * @param id
     *            the id
     * @return the team
     * @throws IOException
     *             the io exception
     */
    public GHTeam getTeam(int id) throws IOException {
        return retrieve().to("/teams/" + id, GHTeam.class).wrapUp(this);
    }

    /**
     * Public events visible to you. Equivalent of what's displayed on https://github.com/
     *
     * @return the events
     * @throws IOException
     *             the io exception
     */
    public List<GHEventInfo> getEvents() throws IOException {
        GHEventInfo[] events = retrieve().to("/events", GHEventInfo[].class);
        for (GHEventInfo e : events)
            e.wrapUp(this);
        return Arrays.asList(events);
    }

    /**
     * Gets a single gist by ID.
     *
     * @param id
     *            the id
     * @return the gist
     * @throws IOException
     *             the io exception
     */
    public GHGist getGist(String id) throws IOException {
        return retrieve().to("/gists/" + id, GHGist.class).wrapUp(this);
    }

    /**
     * Create gist gh gist builder.
     *
     * @return the gh gist builder
     */
    public GHGistBuilder createGist() {
        return new GHGistBuilder(this);
    }

    /**
     * Parses the GitHub event object.
     * <p>
     * This is primarily intended for receiving a POST HTTP call from a hook. Unfortunately, hook script payloads aren't
     * self-descriptive, so you need to know the type of the payload you are expecting.
     *
     * @param <T>
     *            the type parameter
     * @param r
     *            the r
     * @param type
     *            the type
     * @return the t
     * @throws IOException
     *             the io exception
     */
    public <T extends GHEventPayload> T parseEventPayload(Reader r, Class<T> type) throws IOException {
        T t = MAPPER.readValue(r, type);
        t.wrapUp(this);
        return t;
    }

    /**
     * Creates a new repository.
     *
     * @param name
     *            the name
     * @param description
     *            the description
     * @param homepage
     *            the homepage
     * @param isPublic
     *            the is public
     * @return Newly created repository.
     * @throws IOException
     *             the io exception
     * @deprecated Use {@link #createRepository(String)} that uses a builder pattern to let you control every aspect.
     */
    @Deprecated
    public GHRepository createRepository(String name, String description, String homepage, boolean isPublic)
            throws IOException {
        return createRepository(name).description(description).homepage(homepage).private_(!isPublic).create();
    }

    /**
     * Starts a builder that creates a new repository.
     *
     * <p>
     * You use the returned builder to set various properties, then call {@link GHCreateRepositoryBuilder#create()} to
     * finally create a repository.
     *
     * <p>
     * To create a repository in an organization, see
     * {@link GHOrganization#createRepository(String, String, String, GHTeam, boolean)}
     *
     * @param name
     *            the name
     * @return the gh create repository builder
     */
    public GHCreateRepositoryBuilder createRepository(String name) {
        return new GHCreateRepositoryBuilder(this, "/user/repos", name);
    }

    /**
     * Creates a new authorization.
     * <p>
     * The token created can be then used for {@link GitHub#connectUsingOAuth(String)} in the future.
     *
     * @param scope
     *            the scope
     * @param note
     *            the note
     * @param noteUrl
     *            the note url
     * @return the gh authorization
     * @throws IOException
     *             the io exception
     * @see <a href="http://developer.github.com/v3/oauth/#create-a-new-authorization">Documentation</a>
     */
    public GHAuthorization createToken(Collection<String> scope, String note, String noteUrl) throws IOException {
        Requester requester = new Requester(this).with("scopes", scope).with("note", note).with("note_url", noteUrl);

        return requester.method("POST").to("/authorizations", GHAuthorization.class).wrap(this);
    }

    /**
     * Creates a new authorization using an OTP.
     * <p>
     * Start by running createToken, if exception is thrown, prompt for OTP from user
     * <p>
     * Once OTP is received, call this token request
     * <p>
     * The token created can be then used for {@link GitHub#connectUsingOAuth(String)} in the future.
     *
     * @param scope
     *            the scope
     * @param note
     *            the note
     * @param noteUrl
     *            the note url
     * @param OTP
     *            the otp
     * @return the gh authorization
     * @throws IOException
     *             the io exception
     * @see <a href="http://developer.github.com/v3/oauth/#create-a-new-authorization">Documentation</a>
     */
    public GHAuthorization createToken(Collection<String> scope, String note, String noteUrl, Supplier<String> OTP)
            throws IOException {
        try {
            return createToken(scope, note, noteUrl);
        } catch (GHOTPRequiredException ex) {
            String OTPstring = OTP.get();
            Requester requester = new Requester(this).with("scopes", scope)
                    .with("note", note)
                    .with("note_url", noteUrl);
            // Add the OTP from the user
            requester.setHeader("x-github-otp", OTPstring);
            return requester.method("POST").to("/authorizations", GHAuthorization.class).wrap(this);
        }
    }

    /**
     * Create or get auth gh authorization.
     *
     * @param clientId
     *            the client id
     * @param clientSecret
     *            the client secret
     * @param scopes
     *            the scopes
     * @param note
     *            the note
     * @param note_url
     *            the note url
     * @return the gh authorization
     * @throws IOException
     *             the io exception
     * @see <a href=
     *      "https://developer.github.com/v3/oauth_authorizations/#get-or-create-an-authorization-for-a-specific-app">docs</a>
     */
    public GHAuthorization createOrGetAuth(String clientId,
            String clientSecret,
            List<String> scopes,
            String note,
            String note_url) throws IOException {
        Requester requester = new Requester(this).with("client_secret", clientSecret)
                .with("scopes", scopes)
                .with("note", note)
                .with("note_url", note_url);

        return requester.method("PUT").to("/authorizations/clients/" + clientId, GHAuthorization.class);
    }

    /**
     * Delete auth.
     *
     * @param id
     *            the id
     * @throws IOException
     *             the io exception
     * @see <a href="https://developer.github.com/v3/oauth_authorizations/#delete-an-authorization">Delete an
     *      authorization</a>
     */
    public void deleteAuth(long id) throws IOException {
        retrieve().method("DELETE").to("/authorizations/" + id);
    }

    /**
     * Check auth gh authorization.
     *
     * @param clientId
     *            the client id
     * @param accessToken
     *            the access token
     * @return the gh authorization
     * @throws IOException
     *             the io exception
     * @see <a href="https://developer.github.com/v3/oauth_authorizations/#check-an-authorization">Check an
     *      authorization</a>
     */
    public GHAuthorization checkAuth(@Nonnull String clientId, @Nonnull String accessToken) throws IOException {
        return retrieve().to("/applications/" + clientId + "/tokens/" + accessToken, GHAuthorization.class);
    }

    /**
     * Reset auth gh authorization.
     *
     * @param clientId
     *            the client id
     * @param accessToken
     *            the access token
     * @return the gh authorization
     * @throws IOException
     *             the io exception
     * @see <a href="https://developer.github.com/v3/oauth_authorizations/#reset-an-authorization">Reset an
     *      authorization</a>
     */
    public GHAuthorization resetAuth(@Nonnull String clientId, @Nonnull String accessToken) throws IOException {
        return retrieve().method("POST")
                .to("/applications/" + clientId + "/tokens/" + accessToken, GHAuthorization.class);
    }

    /**
     * Returns a list of all authorizations.
     *
     * @return the paged iterable
     * @throws IOException
     *             the io exception
     * @see <a href="https://developer.github.com/v3/oauth_authorizations/#list-your-authorizations">List your
     *      authorizations</a>
     */
    public PagedIterable<GHAuthorization> listMyAuthorizations() throws IOException {
        return retrieve().asPagedIterable("/authorizations", GHAuthorization[].class, item -> item.wrap(GitHub.this));
    }

    /**
     * Returns the GitHub App associated with the authentication credentials used.
     * <p>
     * You must use a JWT to access this endpoint.
     *
     * @return the app
     * @throws IOException
     *             the io exception
     * @see <a href="https://developer.github.com/v3/apps/#get-the-authenticated-github-app">Get the authenticated
     *      GitHub App</a>
     */
    @Preview
    @Deprecated
    public GHApp getApp() throws IOException {
        return retrieve().withPreview(MACHINE_MAN).to("/app", GHApp.class).wrapUp(this);
    }

    /**
     * Ensures that the credential is valid.
     *
     * @return the boolean
     */
    public boolean isCredentialValid() {
        try {
            retrieve().to("/user", GHUser.class);
            return true;
        } catch (IOException e) {
            if (LOGGER.isLoggable(FINE))
                LOGGER.log(FINE,
                        "Exception validating credentials on " + this.apiUrl + " with login '" + this.login + "' " + e,
                        e);
            return false;
        }
    }

    /**
     * Provides a list of GitHub's IP addresses.
     *
     * @return an instance of {@link GHMeta}
     * @throws IOException
     *             if the credentials supplied are invalid or if you're trying to access it as a GitHub App via the JWT
     *             authentication
     * @see <a href="https://developer.github.com/v3/meta/#meta">Get Meta</a>
     */
    public GHMeta getMeta() throws IOException {
        return retrieve().to("/meta", GHMeta.class);
    }

    GHUser intern(GHUser user) throws IOException {
        if (user == null)
            return user;

        // if we already have this user in our map, use it
        GHUser u = users.get(user.getLogin());
        if (u != null)
            return u;

        // if not, remember this new user
        users.putIfAbsent(user.getLogin(), user);
        return user;
    }

    /**
     * Gets project.
     *
     * @param id
     *            the id
     * @return the project
     * @throws IOException
     *             the io exception
     */
    public GHProject getProject(long id) throws IOException {
        return retrieve().withPreview(INERTIA).to("/projects/" + id, GHProject.class).wrap(this);
    }

    /**
     * Gets project column.
     *
     * @param id
     *            the id
     * @return the project column
     * @throws IOException
     *             the io exception
     */
    public GHProjectColumn getProjectColumn(long id) throws IOException {
        return retrieve().withPreview(INERTIA).to("/projects/columns/" + id, GHProjectColumn.class).wrap(this);
    }

    /**
     * Gets project card.
     *
     * @param id
     *            the id
     * @return the project card
     * @throws IOException
     *             the io exception
     */
    public GHProjectCard getProjectCard(long id) throws IOException {
        return retrieve().withPreview(INERTIA).to("/projects/columns/cards/" + id, GHProjectCard.class).wrap(this);
    }

    private static class GHApiInfo {
        private String rate_limit_url;

        void check(String apiUrl) throws IOException {
            if (rate_limit_url == null)
                throw new IOException(apiUrl + " doesn't look like GitHub API URL");

            // make sure that the URL is legitimate
            new URL(rate_limit_url);
        }
    }

    /**
     * Tests the connection.
     *
     * <p>
     * Verify that the API URL and credentials are valid to access this GitHub.
     *
     * <p>
     * This method returns normally if the endpoint is reachable and verified to be GitHub API URL. Otherwise this
     * method throws {@link IOException} to indicate the problem.
     *
     * @throws IOException
     *             the io exception
     */
    public void checkApiUrlValidity() throws IOException {
        try {
            retrieve().to("/", GHApiInfo.class).check(apiUrl);
        } catch (IOException e) {
            if (isPrivateModeEnabled()) {
                throw (IOException) new IOException(
                        "GitHub Enterprise server (" + apiUrl + ") with private mode enabled").initCause(e);
            }
            throw e;
        }
    }

    /**
     * Checks if a GitHub Enterprise server is configured in private mode.
     *
     * In private mode response looks like:
     *
     * <pre>
     *  $ curl -i https://github.mycompany.com/api/v3/
     *     HTTP/1.1 401 Unauthorized
     *     Server: GitHub.com
     *     Date: Sat, 05 Mar 2016 19:45:01 GMT
     *     Content-Type: application/json; charset=utf-8
     *     Content-Length: 130
     *     Status: 401 Unauthorized
     *     X-GitHub-Media-Type: github.v3
     *     X-XSS-Protection: 1; mode=block
     *     X-Frame-Options: deny
     *     Content-Security-Policy: default-src 'none'
     *     Access-Control-Allow-Credentials: true
     *     Access-Control-Expose-Headers: ETag, Link, X-GitHub-OTP, X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset, X-OAuth-Scopes, X-Accepted-OAuth-Scopes, X-Poll-Interval
     *     Access-Control-Allow-Origin: *
     *     X-GitHub-Request-Id: dbc70361-b11d-4131-9a7f-674b8edd0411
     *     Strict-Transport-Security: max-age=31536000; includeSubdomains; preload
     *     X-Content-Type-Options: nosniff
     * </pre>
     *
     * @return {@code true} if private mode is enabled. If it tries to use this method with GitHub, returns {@code
     * false}.
     */
    private boolean isPrivateModeEnabled() {
        try {
            HttpURLConnection uc = getConnector().connect(getApiURL("/"));
            try {
                return uc.getResponseCode() == HTTP_UNAUTHORIZED && uc.getHeaderField("X-GitHub-Media-Type") != null;
            } finally {
                // ensure that the connection opened by getResponseCode gets closed
                try {
                    IOUtils.closeQuietly(uc.getInputStream());
                } catch (IOException ignore) {
                    // ignore
                }
                IOUtils.closeQuietly(uc.getErrorStream());
            }
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Search commits.
     *
     * @return the gh commit search builder
     */
    @Preview
    @Deprecated
    public GHCommitSearchBuilder searchCommits() {
        return new GHCommitSearchBuilder(this);
    }

    /**
     * Search issues.
     *
     * @return the gh issue search builder
     */
    public GHIssueSearchBuilder searchIssues() {
        return new GHIssueSearchBuilder(this);
    }

    /**
     * Search users.
     *
     * @return the gh user search builder
     */
    public GHUserSearchBuilder searchUsers() {
        return new GHUserSearchBuilder(this);
    }

    /**
     * Search repositories.
     *
     * @return the gh repository search builder
     */
    public GHRepositorySearchBuilder searchRepositories() {
        return new GHRepositorySearchBuilder(this);
    }

    /**
     * Search content.
     *
     * @return the gh content search builder
     */
    public GHContentSearchBuilder searchContent() {
        return new GHContentSearchBuilder(this);
    }

    /**
     * List all the notifications.
     *
     * @return the gh notification stream
     */
    public GHNotificationStream listNotifications() {
        return new GHNotificationStream(this, "/notifications");
    }

    /**
     * This provides a dump of every public repository, in the order that they were created.
     *
     * @return the paged iterable
     * @see <a href="https://developer.github.com/v3/repos/#list-all-public-repositories">documentation</a>
     */
    public PagedIterable<GHRepository> listAllPublicRepositories() {
        return listAllPublicRepositories(null);
    }

    /**
     * This provides a dump of every public repository, in the order that they were created.
     *
     * @param since
     *            The numeric ID of the last Repository that you’ve seen. See {@link GHRepository#getId()}
     * @return the paged iterable
     * @see <a href="https://developer.github.com/v3/repos/#list-all-public-repositories">documentation</a>
     */
    public PagedIterable<GHRepository> listAllPublicRepositories(final String since) {
        return retrieve().with("since", since)
                .asPagedIterable("/repositories", GHRepository[].class, item -> item.wrap(GitHub.this));
    }

    /**
     * Render a Markdown document in raw mode.
     *
     * <p>
     * It takes a Markdown document as plaintext and renders it as plain Markdown without a repository context (just
     * like a README.md file is rendered – this is the simplest way to preview a readme online).
     *
     * @param text
     *            the text
     * @return the reader
     * @throws IOException
     *             the io exception
     * @see GHRepository#renderMarkdown(String, MarkdownMode) GHRepository#renderMarkdown(String, MarkdownMode)
     */
    public Reader renderMarkdown(String text) throws IOException {
        return new InputStreamReader(new Requester(this).with(new ByteArrayInputStream(text.getBytes("UTF-8")))
                .contentType("text/plain;charset=UTF-8")
                .asStream("/markdown/raw"), "UTF-8");
    }

    static URL parseURL(String s) {
        try {
            return s == null ? null : new URL(s);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Invalid URL: " + s);
        }
    }

    static Date parseDate(String timestamp) {
        if (timestamp == null)
            return null;
        for (String f : TIME_FORMATS) {
            try {
                SimpleDateFormat df = new SimpleDateFormat(f);
                df.setTimeZone(TimeZone.getTimeZone("GMT"));
                return df.parse(timestamp);
            } catch (ParseException e) {
                // try next
            }
        }
        throw new IllegalStateException("Unable to parse the timestamp: " + timestamp);
    }

    static String printDate(Date dt) {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        df.setTimeZone(TimeZone.getTimeZone("GMT"));
        return df.format(dt);
    }

    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[] TIME_FORMATS = { "yyyy/MM/dd HH:mm:ss ZZZZ", "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.S'Z'" // GitHub App endpoints return a different date format
    };

    static {
        MAPPER.setVisibility(new Std(NONE, NONE, NONE, NONE, ANY));
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        MAPPER.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
        MAPPER.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);
    }

    static final String GITHUB_URL = "https://api.github.com";

    private static final Logger LOGGER = Logger.getLogger(GitHub.class.getName());
}
