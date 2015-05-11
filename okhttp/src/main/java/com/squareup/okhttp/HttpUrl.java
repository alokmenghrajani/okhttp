/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp;

import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import okio.Buffer;

/**
 * A uniform resource locator (URL) with a scheme of either {@code http} or {@code https}. Use this
 * class to compose and decompose Internet addresses. For example, this code will compose and print
 * a URL for Google search: <pre>   {@code
 *
 *   HttpUrl url = new HttpUrl.Builder()
 *       .scheme("https")
 *       .host("www.google.com")
 *       .addPathSegment("search")
 *       .addQueryParameter("q", "polar bears")
 *       .build();
 *   System.out.println(url);
 * }</pre>
 *
 * which prints: <pre>   {@code
 *
 *     https://www.google.com/search?q=polar+bears
 * }</pre>
 *
 * As another example, this code prints the human-readable query parameters of a Twitter search:
 * <pre>   {@code
 *
 *   HttpUrl url = HttpUrl.parse("https://twitter.com/search?q=cute%20%23puppies&f=images");
 *   for (int i = 0, size = url.querySize(); i < size; i++) {
 *     System.out.println(url.queryParameterName(i) + ": " + url.queryParameterValue(i));
 *   }
 * }</pre>
 *
 * which prints: <pre>   {@code
 *
 *   q: cute #puppies
 *   f: images
 * }</pre>
 *
 * In addition to composing URLs from their component parts and decomposing URLs into their
 * component parts, this class implements relative URL resolution: what address you'd reach by
 * clicking a relative link on a specified page. For example: <pre>   {@code
 *
 *   HttpUrl base = HttpUrl.parse("https://www.youtube.com/user/WatchTheDaily/videos");
 *   HttpUrl link = base.resolve("../../watch?v=cbP2N1BQdYc");
 *   System.out.println(link);
 * }</pre>
 *
 * which prints <pre>   {@code
 *
 *   https://www.youtube.com/watch?v=cbP2N1BQdYc
 * }</pre>
 *
 * <h3>What's in a URL?</h3>
 *
 * A URL has several components.
 *
 * <h4>Scheme</h4>
 * Sometimes referred to as <i>protocol</i>, A URL's scheme describes what mechanism should be used
 * to retrieve the resource. Although URLs have many schemes ({@code mailto}, {@code file}, {@code
 * ftp}), this class only supports {@code http} and {@code https}. Use {@link URI java.net.URI} for
 * URLs with arbitrary schemes.
 *
 * <h4>Username and Password</h4>
 * Username and password are either present, or the empty string {@code ""} if absent. This class
 * offers no mechanism to differentiate empty from absent. Neither of these components are popular
 * in practice. Typically HTTP applications use other mechanisms for user identification and
 * authentication.
 *
 * <h4>Host</h4>
 * The host identifies the webserver that serves the URL's resource. It is either a hostname like
 * {@code square.com} or {@code localhost}, an IPv4 address like {@code 192.168.0.1}, or an IPv6
 * address like {@code ::1}.
 *
 * <p>Usually a webserver is reachable with multiple identifiers: its IP addresses, registered
 * domain names, and even {@code localhost} when connecting from the server itself. Each of a
 * webserver's names is a distinct URL and they are not interchangeable. For example, even if
 * {@code http://square.github.io/dagger} and {@code http://google.github.io/dagger} are served by
 * the same IP address, the two URLs identify different resources.
 *
 * <h4>Port</h4>
 * The port used to connect to the webserver. By default this is 80 for HTTP and 443 for HTTPS. This
 * class never returns -1 for the port: if no port is explicitly specified in the URL then the
 * scheme's default is used.
 *
 * <h4>Path</h4>
 * The path identifies a specific resource on the host. Paths have a hierarchical structure like
 * "/square/okhttp/issues/1486". Each path segment is prefixed with "/". This class offers methods
 * to compose and decompose paths by segment. If a path's last segment is the empty string, then the
 * path ends with "/". This class always builds non-empty paths: if the path is omitted it defaults
 * to "/", which is a path whose only segment is the empty string.
 *
 * <h4>Query</h4>
 * The query is optional: it can be null, empty, or non-empty. For many HTTP URLs the query string
 * is subdivided into a collection of name-value parameters. This class offers methods to set the
 * query as the single string, or as individual name-value parameters. With name-value parameters
 * the values are optional and names may be repeated.
 *
 * <h4>Fragment</h4>
 * The fragment is optional: it can be null, empty, or non-empty. Unlike host, port, path, and query
 * the fragment is not sent to the webserver: it's private to the client.
 *
 * <h3>Encoding and Canonicalization</h3>
 * TODO.
 *
 * <h3>Why another URL model?</h3>
 * TODO.
 *
 */
public final class HttpUrl {
  private static final char[] HEX_DIGITS =
      { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };
  private static final String USERNAME_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#";
  private static final String PASSWORD_ENCODE_SET = " \"':;<=>@[]\\^`{}|/\\?#";
  private static final String PATH_SEGMENT_ENCODE_SET = " \"<>^`{}|/\\?#";
  private static final String QUERY_ENCODE_SET = " \"'<>#";
  private static final String QUERY_COMPONENT_ENCODE_SET = " \"'<>#&=";
  private static final String FRAGMENT_ENCODE_SET = "";

  /** Either "http" or "https". */
  private final String scheme;

  /** Canonical username. */
  private final String username;

  /** Canonical password. */
  private final String password;

  /** Canonical hostname. */
  private final String host;

  /** Either 80, 443 or a user-specified port. In range [1..65535]. */
  private final int port;

  /**
   * A list of canonical path segments. This list always contains at least one element, which may
   * be the empty string. Each segment is formatted with a leading '/', so if path segments were
   * ["a", "b", ""], then the encoded path would be "/a/b/".
   */
  private final List<String> pathSegments;

  /**
   * Alternating, encoded query names and values, or null for no query. Names may be empty or
   * non-empty, but never null. Values are null if the name has no corresponding '=' separator, or
   * empty, or non-empty.
   */
  private final List<String> queryNamesAndValues;

  /** Canonical fragment. */
  private final String fragment;

  /** Canonical URL. */
  private final String url;

  private HttpUrl(String scheme, String username, String password, String host, int port,
      List<String> pathSegments, List<String> queryNamesAndValues, String fragment, String url) {
    this.scheme = scheme;
    this.username = username;
    this.password = password;
    this.host = host;
    this.port = port;
    this.pathSegments = Util.immutableList(pathSegments);
    this.queryNamesAndValues = queryNamesAndValues != null
        ? Util.immutableList(queryNamesAndValues)
        : null;
    this.fragment = fragment;
    this.url = url;
  }

  public URL url() {
    throw new UnsupportedOperationException(); // TODO(jwilson).
  }

  public URI uri() throws IOException {
    throw new UnsupportedOperationException(); // TODO(jwilson).
  }

  /** Returns either "http" or "https". */
  public String scheme() {
    return scheme;
  }

  public boolean isHttps() {
    return scheme.equals("https");
  }

  /** Returns the username, or an empty string if none is set. */
  public String username() {
    return username;
  }

  public String decodeUsername() {
    return percentDecode(username, false);
  }

  /** Returns the password, or an empty string if none is set. */
  public String password() {
    return password;
  }

  /** Returns the decoded password, or an empty string if none is present. */
  public String decodePassword() {
    return password != null ? percentDecode(password, false) : null;
  }

  /**
   * Returns the host address suitable for use with {@link InetAddress#getAllByName(String)}. May
   * be:
   * <ul>
   *   <li>A regular host name, like {@code android.com}.
   *   <li>An IPv4 address, like {@code 127.0.0.1}.
   *   <li>An IPv6 address, like {@code ::1}. Note that there are no square braces.
   *   <li>An encoded IDN, like {@code xn--n3h.net}.
   * </ul>
   */
  public String host() {
    return host;
  }

  /**
   * Returns the decoded (potentially non-ASCII) hostname. The returned string may contain non-ASCII
   * characters and is <strong>not suitable</strong> for DNS lookups; for that use {@link
   * #host}. For example, this may return {@code ☃.net} which is a user-displayable IDN that cannot
   * be used for DNS lookups without encoding.
   */
  public String decodeHost() {
    throw new UnsupportedOperationException(); // TODO(jwilson).
  }

  /**
   * Returns the explicitly-specified port if one was provided, or the default port for this URL's
   * scheme. For example, this returns 8443 for {@code https://square.com:8443/} and 443 for {@code
   * https://square.com/}. The result is in {@code [1..65535]}.
   */
  public int port() {
    return port;
  }

  /**
   * Returns 80 if {@code scheme.equals("http")}, 443 if {@code scheme.equals("https")} and -1
   * otherwise.
   */
  public static int defaultPort(String scheme) {
    if (scheme.equals("http")) {
      return 80;
    } else if (scheme.equals("https")) {
      return 443;
    } else {
      return -1;
    }
  }

  /**
   * Returns the entire path of this URL, encoded for use in HTTP resource resolution. The
   * returned path is always nonempty and is prefixed with {@code /}.
   */
  public String path() {
    StringBuilder result = new StringBuilder();
    pathSegmentsToString(result, pathSegments);
    return result.toString();
  }

  static void pathSegmentsToString(StringBuilder out, List<String> pathSegments) {
    for (int i = 0, size = pathSegments.size(); i < size; i++) {
      out.append('/');
      out.append(pathSegments.get(i));
    }
  }

  public List<String> pathSegments() {
    return pathSegments;
  }

  public List<String> decodePathSegments() {
    List<String> result = new ArrayList<>();
    for (int i = 0, size = pathSegments.size(); i < size; i++) {
      result.add(percentDecode(pathSegments.get(i), false));
    }
    return Util.immutableList(result);
  }

  /**
   * Returns the query of this URL, encoded for use in HTTP resource resolution. The returned string
   * may be null (for URLs with no query), empty (for URLs with an empty query) or non-empty (all
   * other URLs).
   */
  public String query() {
    if (queryNamesAndValues == null) return null; // No query.
    StringBuilder result = new StringBuilder();
    namesAndValuesToQueryString(result, queryNamesAndValues);
    return result.toString();
  }

  static void namesAndValuesToQueryString(StringBuilder out, List<String> namesAndValues) {
    for (int i = 0, size = namesAndValues.size(); i < size; i += 2) {
      String name = namesAndValues.get(i);
      String value = namesAndValues.get(i + 1);
      if (i > 0) out.append('&');
      out.append(name);
      if (value != null) {
        out.append('=');
        out.append(value);
      }
    }
  }

  /**
   * Cuts {@code encodedQuery} up into alternating parameter names and values. This divides a
   * query string like {@code subject=math&easy&problem=5-2=3} into the list {@code ["subject",
   * "math", "easy", null, "problem", "5-2=3"]}. Note that values may be null and may contain
   * '=' characters.
   */
  static List<String> queryStringToNamesAndValues(String encodedQuery) {
    List<String> result = new ArrayList<>();
    int pos = 0;
    while (pos < encodedQuery.length()) {
      int ampersandOffset = encodedQuery.indexOf('&', pos);
      if (ampersandOffset == -1) ampersandOffset = encodedQuery.length();

      int equalsOffset = encodedQuery.indexOf('=', pos);
      if (equalsOffset == -1 || equalsOffset > ampersandOffset) {
        result.add(encodedQuery.substring(pos, ampersandOffset));
        result.add(null); // No value for this name.
      } else {
        result.add(encodedQuery.substring(pos, equalsOffset));
        result.add(encodedQuery.substring(equalsOffset + 1, ampersandOffset));
      }
      pos = ampersandOffset + 1;
    }
    return result;
  }

  public String decodeQuery() {
    if (queryNamesAndValues == null) return null; // No query.

    Buffer result = new Buffer();
    for (int i = 0, size = queryNamesAndValues.size(); i < size; i += 2) {
      String name = queryNamesAndValues.get(i);
      String value = queryNamesAndValues.get(i + 1);
      if (i > 0) result.writeByte('&');
      percentDecode(result, name, 0, name.length(), true);
      if (value != null) {
        result.writeByte('=');
        percentDecode(result, value, 0, value.length(), true);
      }
    }
    return result.readUtf8();
  }

  public int querySize() {
    return queryNamesAndValues != null ? queryNamesAndValues.size() / 2 : 0;
  }

  /**
   * Returns the first query parameter named {@code name} decoded using UTF-8, or null if there is
   * no such query parameter.
   */
  public String queryParameter(String name) {
    if (queryNamesAndValues == null) return null;
    String encodedName = canonicalize(name, QUERY_COMPONENT_ENCODE_SET, false, true);
    for (int i = 0, size = queryNamesAndValues.size(); i < size; i += 2) {
      if (encodedName.equals(queryNamesAndValues.get(i))) {
        String value = queryNamesAndValues.get(i + 1);
        return value != null ? percentDecode(value, true) : null;
      }
    }
    return null;
  }

  public Set<String> queryParameterNames() {
    if (queryNamesAndValues == null) return Collections.emptySet();
    Set<String> result = new LinkedHashSet<>();
    for (int i = 0, size = queryNamesAndValues.size(); i < size; i += 2) {
      result.add(percentDecode(queryNamesAndValues.get(i), true));
    }
    return Collections.unmodifiableSet(result);
  }

  public List<String> queryParameterValues(String name) {
    if (queryNamesAndValues == null) return Collections.emptyList();
    String encodedName = canonicalize(name, QUERY_COMPONENT_ENCODE_SET, false, true);
    List<String> result = new ArrayList<>();
    for (int i = 0, size = queryNamesAndValues.size(); i < size; i += 2) {
      if (encodedName.equals(queryNamesAndValues.get(i))) {
        String value = queryNamesAndValues.get(i + 1);
        result.add(value != null ? percentDecode(value, true) : null);
      }
    }
    return Collections.unmodifiableList(result);
  }

  public String queryParameterName(int index) {
    return percentDecode(queryNamesAndValues.get(index * 2), true);
  }

  public String queryParameterValue(int index) {
    String value = queryNamesAndValues.get(index * 2 + 1);
    return value != null ? percentDecode(value, true) : null;
  }

  public String fragment() {
    return fragment;
  }

  public String decodeFragment() {
    return fragment != null ? percentDecode(fragment, false) : null;
  }

  /**
   * Returns the URL that would be retrieved by following {@code link} from this URL.
   *
   * TODO: explain better.
   */
  public HttpUrl resolve(String link) {
    return new Builder().parse(this, link);
  }

  public Builder newBuilder() {
    Builder result = new Builder();
    result.scheme = scheme;
    result.username = username;
    result.password = password;
    result.host = host;
    result.port = port;
    result.pathSegments.clear();
    result.pathSegments.addAll(pathSegments);
    result.queryNamesAndValues = queryNamesAndValues != null
        ? new ArrayList<>(queryNamesAndValues)
        : null;
    result.fragment = fragment;
    return result;
  }

  /**
   * Returns a new {@code OkUrl} representing {@code url} if it is a well-formed HTTP or HTTPS URL,
   * or null if it isn't.
   */
  public static HttpUrl parse(String url) {
    return new Builder().parse(null, url);
  }

  public static HttpUrl get(URL url) {
    return parse(url.toString());
  }

  public static HttpUrl get(URI uri) {
    return parse(uri.toString());
  }

  @Override public boolean equals(Object o) {
    return o instanceof HttpUrl && ((HttpUrl) o).url.equals(url);
  }

  @Override public int hashCode() {
    return url.hashCode();
  }

  @Override public String toString() {
    return url;
  }

  public static final class Builder {
    String scheme;
    String username = "";
    String password = "";
    String host;
    int port = -1;
    final List<String> pathSegments = new ArrayList<>();
    List<String> queryNamesAndValues;
    String fragment;

    public Builder() {
      pathSegments.add(""); // The default path is '/' which needs a trailing space.
    }

    private Builder(HttpUrl url) {
      throw new UnsupportedOperationException(); // TODO(jwilson)
    }

    public Builder scheme(String scheme) {
      if (scheme == null) {
        throw new IllegalArgumentException("scheme == null");
      } else if (scheme.equalsIgnoreCase("http")) {
        this.scheme = "http";
      } else if (scheme.equalsIgnoreCase("https")) {
        this.scheme = "https";
      } else {
        throw new IllegalArgumentException("unexpected scheme: " + scheme);
      }
      return this;
    }

    public Builder username(String username) {
      if (username == null) throw new IllegalArgumentException("username == null");
      this.username = canonicalize(username, USERNAME_ENCODE_SET, false, false);
      return this;
    }

    public Builder encodedUsername(String encodedUsername) {
      if (encodedUsername == null) throw new IllegalArgumentException("encodedUsername == null");
      this.username = canonicalize(encodedUsername, USERNAME_ENCODE_SET, true, false);
      return this;
    }

    public Builder password(String password) {
      if (password == null) throw new IllegalArgumentException("password == null");
      this.password = canonicalize(password, PASSWORD_ENCODE_SET, false, false);
      return this;
    }

    public Builder encodedPassword(String encodedPassword) {
      if (encodedPassword == null) throw new IllegalArgumentException("encodedPassword == null");
      this.password = canonicalize(encodedPassword, PASSWORD_ENCODE_SET, true, false);
      return this;
    }

    /**
     * @param host either a regular hostname, International Domain Name, IPv4 address, or IPv6
     *     address.
     */
    public Builder host(String host) {
      if (host == null) throw new IllegalArgumentException("host == null");
      String encoded = canonicalizeHost(host, 0, host.length());
      if (encoded == null) throw new IllegalArgumentException("unexpected host: " + host);
      this.host = encoded;
      return this;
    }

    public Builder port(int port) {
      if (port <= 0 || port > 65535) throw new IllegalArgumentException("unexpected port: " + port);
      this.port = port;
      return this;
    }

    public Builder addPathSegment(String pathSegment) {
      if (pathSegment == null) throw new IllegalArgumentException("pathSegment == null");
      push(pathSegment, 0, pathSegment.length(), false, false);
      return this;
    }

    public Builder addEncodedPathSegment(String encodedPathSegment) {
      if (encodedPathSegment == null) {
        throw new IllegalArgumentException("encodedPathSegment == null");
      }
      push(encodedPathSegment, 0, encodedPathSegment.length(), false, true);
      return this;
    }

    public Builder encodedPath(String encodedPath) {
      if (encodedPath == null) throw new IllegalArgumentException("encodedPath == null");
      if (!encodedPath.startsWith("/")) {
        throw new IllegalArgumentException("unexpected encodedPath: " + encodedPath);
      }
      resolvePath(encodedPath, 0, encodedPath.length());
      return this;
    }

    public Builder query(String query) {
      this.queryNamesAndValues = query != null
          ? queryStringToNamesAndValues(canonicalize(query, QUERY_ENCODE_SET, false, true))
          : null;
      return this;
    }

    public Builder encodedQuery(String encodedQuery) {
      this.queryNamesAndValues = encodedQuery != null
          ? queryStringToNamesAndValues(canonicalize(encodedQuery, QUERY_ENCODE_SET, true, true))
          : null;
      return this;
    }

    /** Encodes the query parameter using UTF-8 and adds it to this URL's query string. */
    public Builder addQueryParameter(String name, String value) {
      if (name == null) throw new IllegalArgumentException("name == null");
      if (queryNamesAndValues == null) queryNamesAndValues = new ArrayList<>();
      queryNamesAndValues.add(canonicalize(name, QUERY_COMPONENT_ENCODE_SET, false, true));
      queryNamesAndValues.add(value != null
          ? canonicalize(value, QUERY_COMPONENT_ENCODE_SET, false, true)
          : null);
      return this;
    }

    /** Adds the pre-encoded query parameter to this URL's query string. */
    public Builder addEncodedQueryParameter(String encodedName, String encodedValue) {
      if (encodedName == null) throw new IllegalArgumentException("encodedName == null");
      if (queryNamesAndValues == null) queryNamesAndValues = new ArrayList<>();
      queryNamesAndValues.add(canonicalize(encodedName, QUERY_COMPONENT_ENCODE_SET, true, true));
      queryNamesAndValues.add(encodedValue != null
          ? canonicalize(encodedValue, QUERY_COMPONENT_ENCODE_SET, true, true)
          : null);
      return this;
    }

    public Builder setQueryParameter(String name, String value) {
      removeAllQueryParameters(name);
      addQueryParameter(name, value);
      return this;
    }

    public Builder setEncodedQueryParameter(String encodedName, String encodedValue) {
      removeAllEncodedQueryParameters(encodedName);
      addEncodedQueryParameter(encodedName, encodedValue);
      return this;
    }

    public Builder removeAllQueryParameters(String name) {
      if (name == null) throw new IllegalArgumentException("name == null");
      if (queryNamesAndValues == null) return this;
      String nameToRemove = canonicalize(name, QUERY_COMPONENT_ENCODE_SET, false, true);
      removeAllCanonicalQueryParameters(nameToRemove);
      return this;
    }

    public Builder removeAllEncodedQueryParameters(String encodedName) {
      if (encodedName == null) throw new IllegalArgumentException("name == null");
      if (queryNamesAndValues == null) return this;
      removeAllCanonicalQueryParameters(
          canonicalize(encodedName, QUERY_COMPONENT_ENCODE_SET, true, true));
      return this;
    }

    private void removeAllCanonicalQueryParameters(String canonicalName) {
      for (int i = queryNamesAndValues.size() - 2; i >= 0; i -= 2) {
        if (canonicalName.equals(queryNamesAndValues.get(i))) {
          queryNamesAndValues.remove(i + 1);
          queryNamesAndValues.remove(i);
          if (queryNamesAndValues.isEmpty()) {
            queryNamesAndValues = null;
            return;
          }
        }
      }
    }

    public Builder fragment(String fragment) {
      if (fragment == null) throw new IllegalArgumentException("fragment == null");
      this.fragment = canonicalize(fragment, FRAGMENT_ENCODE_SET, false, false);
      return this;
    }

    public Builder encodedFragment(String encodedFragment) {
      if (encodedFragment == null) throw new IllegalArgumentException("encodedFragment == null");
      this.fragment = canonicalize(encodedFragment, FRAGMENT_ENCODE_SET, true, false);
      return this;
    }

    public HttpUrl build() {
      if (scheme == null) throw new IllegalStateException("scheme == null");
      if (host == null) throw new IllegalStateException("host == null");

      StringBuilder url = new StringBuilder();
      url.append(scheme);
      url.append("://");

      if (!username.isEmpty() || !password.isEmpty()) {
        url.append(username);
        if (!password.isEmpty()) {
          url.append(':');
          url.append(password);
        }
        url.append('@');
      }

      if (host.indexOf(':') != -1) {
        // Host is an IPv6 address.
        url.append('[');
        url.append(host);
        url.append(']');
      } else {
        url.append(host);
      }

      int defaultPort = defaultPort(scheme);
      int effectivePort = port != -1 ? port : defaultPort;
      if (effectivePort != defaultPort) {
        url.append(':');
        url.append(port);
      }

      pathSegmentsToString(url, pathSegments);

      if (queryNamesAndValues != null) {
        url.append('?');
        namesAndValuesToQueryString(url, queryNamesAndValues);
      }

      if (fragment != null) {
        url.append('#');
        url.append(fragment);
      }

      return new HttpUrl(scheme, username, password, host, effectivePort, pathSegments,
          queryNamesAndValues, fragment, url.toString());
    }

    HttpUrl parse(HttpUrl base, String input) {
      int pos = skipLeadingAsciiWhitespace(input, 0, input.length());
      int limit = skipTrailingAsciiWhitespace(input, pos, input.length());

      // Scheme.
      int schemeDelimiterOffset = schemeDelimiterOffset(input, pos, limit);
      if (schemeDelimiterOffset != -1) {
        if (input.regionMatches(true, pos, "https:", 0, 6)) {
          this.scheme = "https";
          pos += "https:".length();
        } else if (input.regionMatches(true, pos, "http:", 0, 5)) {
          this.scheme = "http";
          pos += "http:".length();
        } else {
          return null; // Not an HTTP scheme.
        }
      } else if (base != null) {
        this.scheme = base.scheme;
      } else {
        return null; // No scheme.
      }

      // Authority.
      boolean hasUsername = false;
      boolean hasPassword = false;
      int slashCount = slashCount(input, pos, limit);
      if (slashCount >= 2 || base == null || !base.scheme.equals(this.scheme)) {
        // Read an authority if either:
        //  * The input starts with 2 or more slashes. These follow the scheme if it exists.
        //  * The input scheme exists and is different from the base URL's scheme.
        //
        // The structure of an authority is:
        //   username:password@host:port
        //
        // Username, password and port are optional.
        //   [username[:password]@]host[:port]
        pos += slashCount;
        authority:
        while (true) {
          int componentDelimiterOffset = delimiterOffset(input, pos, limit, "@/\\?#");
          int c = componentDelimiterOffset != limit
              ? input.charAt(componentDelimiterOffset)
              : -1;
          switch (c) {
            case '@':
              // User info precedes.
              if (!hasPassword) {
                int passwordColonOffset = delimiterOffset(
                    input, pos, componentDelimiterOffset, ":");
                String canonicalUsername = canonicalize(
                    input, pos, passwordColonOffset, USERNAME_ENCODE_SET, true, false);
                this.username = hasUsername
                    ? this.username + "%40" + canonicalUsername
                    : canonicalUsername;
                if (passwordColonOffset != componentDelimiterOffset) {
                  hasPassword = true;
                  this.password = canonicalize(input, passwordColonOffset + 1,
                      componentDelimiterOffset, PASSWORD_ENCODE_SET, true, false);
                }
                hasUsername = true;
              } else {
                this.password = this.password + "%40" + canonicalize(
                    input, pos, componentDelimiterOffset, PASSWORD_ENCODE_SET, true, false);
              }
              pos = componentDelimiterOffset + 1;
              break;

            case -1:
            case '/':
            case '\\':
            case '?':
            case '#':
              // Host info precedes.
              int portColonOffset = portColonOffset(input, pos, componentDelimiterOffset);
              if (portColonOffset + 1 < componentDelimiterOffset) {
                this.host = canonicalizeHost(input, pos, portColonOffset);
                this.port = parsePort(input, portColonOffset + 1, componentDelimiterOffset);
                if (this.port == -1) return null; // Invalid port.
              } else {
                this.host = canonicalizeHost(input, pos, portColonOffset);
                this.port = defaultPort(this.scheme);
              }
              if (this.host == null) return null; // Invalid host.
              pos = componentDelimiterOffset;
              break authority;
          }
        }
      } else {
        // This is a relative link. Copy over all authority components. Also maybe the path & query.
        this.username = base.username;
        this.password = base.password;
        this.host = base.host;
        this.port = base.port;
        this.pathSegments.clear();
        this.pathSegments.addAll(base.pathSegments);
        if (pos == limit || input.charAt(pos) == '#') {
          this.queryNamesAndValues = base.queryNamesAndValues;
        }
      }

      // Resolve the relative path.
      int pathDelimiterOffset = delimiterOffset(input, pos, limit, "?#");
      resolvePath(input, pos, pathDelimiterOffset);
      pos = pathDelimiterOffset;

      // Query.
      if (pos < limit && input.charAt(pos) == '?') {
        int queryDelimiterOffset = delimiterOffset(input, pos, limit, "#");
        this.queryNamesAndValues = queryStringToNamesAndValues(canonicalize(
            input, pos + 1, queryDelimiterOffset, QUERY_ENCODE_SET, true, true));
        pos = queryDelimiterOffset;
      }

      // Fragment.
      if (pos < limit && input.charAt(pos) == '#') {
        this.fragment = canonicalize(input, pos + 1, limit, FRAGMENT_ENCODE_SET, true, false);
      }

      return build();
    }

    private void resolvePath(String input, int pos, int limit) {
      // Read a delimiter.
      if (pos == limit) {
        // Empty path: keep the base path as-is.
        return;
      }
      char c = input.charAt(pos);
      if (c == '/' || c == '\\') {
        // Absolute path: reset to the default "/".
        pathSegments.clear();
        pathSegments.add("");
        pos++;
      } else {
        // Relative path: clear everything after the last '/'.
        pathSegments.set(pathSegments.size() - 1, "");
      }

      // Read path segments.
      for (int i = pos; i < limit; ) {
        int pathSegmentDelimiterOffset = delimiterOffset(input, i, limit, "/\\");
        boolean segmentHasTrailingSlash = pathSegmentDelimiterOffset < limit;
        push(input, i, pathSegmentDelimiterOffset, segmentHasTrailingSlash, true);
        i = pathSegmentDelimiterOffset;
        if (segmentHasTrailingSlash) i++;
      }
    }

    /** Adds a path segment. If the input is ".." or equivalent, this pops a path segment. */
    private void push(String input, int pos, int limit, boolean addTrailingSlash,
        boolean alreadyEncoded) {
      int segmentLength = limit - pos;
      if ((segmentLength == 2 && input.regionMatches(false, pos, "..", 0, 2))
          || (segmentLength == 4 && input.regionMatches(true, pos, "%2e.", 0, 4))
          || (segmentLength == 4 && input.regionMatches(true, pos, ".%2e", 0, 4))
          || (segmentLength == 6 && input.regionMatches(true, pos, "%2e%2e", 0, 6))) {
        pop();
        return;
      }

      if ((segmentLength == 1 && input.regionMatches(false, pos, ".", 0, 1))
          || (segmentLength == 3 && input.regionMatches(true, pos, "%2e", 0, 3))) {
        return; // Skip '.' path segments.
      }

      String segment = canonicalize(
          input, pos, limit, PATH_SEGMENT_ENCODE_SET, alreadyEncoded, false);
      if (pathSegments.get(pathSegments.size() - 1).isEmpty()) {
        pathSegments.set(pathSegments.size() - 1, segment);
      } else {
        pathSegments.add(segment);
      }

      if (addTrailingSlash) {
        pathSegments.add("");
      }
    }

    /**
     * Removes a path segment. When this method returns the last segment is always "", which means
     * the encoded path will have a trailing '/'.
     *
     * <p>Popping "/a/b/c/" yields "/a/b/". In this case the list of path segments goes from
     * ["a", "b", "c", ""] to ["a", "b", ""].
     *
     * <p>Popping "/a/b/c" also yields "/a/b/". The list of path segments goes from ["a", "b", "c"]
     * to ["a", "b", ""].
     */
    private void pop() {
      String removed = pathSegments.remove(pathSegments.size() - 1);

      // Make sure the path ends with a '/' by either adding an empty string or clearing a segment.
      if (removed.isEmpty() && !pathSegments.isEmpty()) {
        pathSegments.set(pathSegments.size() - 1, "");
      } else {
        pathSegments.add("");
      }
    }

    /**
     * Increments {@code pos} until {@code input[pos]} is not ASCII whitespace. Stops at {@code
     * limit}.
     */
    private int skipLeadingAsciiWhitespace(String input, int pos, int limit) {
      for (int i = pos; i < limit; i++) {
        switch (input.charAt(i)) {
          case '\t':
          case '\n':
          case '\f':
          case '\r':
          case ' ':
            continue;
          default:
            return i;
        }
      }
      return limit;
    }

    /**
     * Decrements {@code limit} until {@code input[limit - 1]} is not ASCII whitespace. Stops at
     * {@code pos}.
     */
    private int skipTrailingAsciiWhitespace(String input, int pos, int limit) {
      for (int i = limit - 1; i >= pos; i--) {
        switch (input.charAt(i)) {
          case '\t':
          case '\n':
          case '\f':
          case '\r':
          case ' ':
            continue;
          default:
            return i + 1;
        }
      }
      return pos;
    }

    /**
     * Returns the index of the ':' in {@code input} that is after scheme characters. Returns -1 if
     * {@code input} does not have a scheme that starts at {@code pos}.
     */
    private static int schemeDelimiterOffset(String input, int pos, int limit) {
      if (limit - pos < 2) return -1;

      char c0 = input.charAt(pos);
      if ((c0 < 'a' || c0 > 'z') && (c0 < 'A' || c0 > 'Z')) return -1; // Not a scheme start char.

      for (int i = pos + 1; i < limit; i++) {
        char c = input.charAt(i);

        if ((c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || c == '+'
            || c == '-'
            || c == '.') {
          continue; // Scheme character. Keep going.
        } else if (c == ':') {
          return i; // Scheme prefix!
        } else {
          return -1; // Non-scheme character before the first ':'.
        }
      }

      return -1; // No ':'; doesn't start with a scheme.
    }

    /** Returns the number of '/' and '\' slashes in {@code input}, starting at {@code pos}. */
    private static int slashCount(String input, int pos, int limit) {
      int slashCount = 0;
      while (pos < limit) {
        char c = input.charAt(pos);
        if (c == '\\' || c == '/') {
          slashCount++;
          pos++;
        } else {
          break;
        }
      }
      return slashCount;
    }

    /**
     * Returns the index of the first character in {@code input} that contains a character in {@code
     * delimiters}. Returns limit if there is no such character.
     */
    private static int delimiterOffset(String input, int pos, int limit, String delimiters) {
      for (int i = pos; i < limit; i++) {
        if (delimiters.indexOf(input.charAt(i)) != -1) return i;
      }
      return limit;
    }

    /** Finds the first ':' in {@code input}, skipping characters between square braces "[...]". */
    private static int portColonOffset(String input, int pos, int limit) {
      for (int i = pos; i < limit; i++) {
        switch (input.charAt(i)) {
          case '[':
            while (++i < limit) {
              if (input.charAt(i) == ']') break;
            }
            break;
          case ':':
            return i;
        }
      }
      return limit; // No colon.
    }

    private static String canonicalizeHost(String input, int pos, int limit) {
      // Start by percent decoding the host. The WHATWG spec suggests doing this only after we've
      // checked for IPv6 square braces. But Chrome does it first, and that's more lenient.
      String percentDecoded = percentDecode(input, pos, limit, false);

      // If the input is encased in square braces "[...]", drop 'em. We have an IPv6 address.
      if (percentDecoded.startsWith("[") && percentDecoded.endsWith("]")) {
        InetAddress inetAddress = decodeIpv6(percentDecoded, 1, percentDecoded.length() - 1);
        return inetAddress != null ? inetAddress.getHostAddress() : null;
      }

      // Do IDN decoding. This converts {@code ☃.net} to {@code xn--n3h.net}.
      String idnDecoded = domainToAscii(percentDecoded);
      if (idnDecoded == null) return null;

      // Confirm that the decoded result doesn't contain any illegal characters.
      int length = idnDecoded.length();
      if (delimiterOffset(idnDecoded, 0, length, "\u0000\t\n\r #%/:?@[\\]") != length) {
        return null;
      }

      return idnDecoded;
    }

    private static InetAddress decodeIpv6(String input, int pos, int limit) {
      try {
        return InetAddress.getByName(input.substring(pos, limit));
      } catch (UnknownHostException e) {
        return null;
      }
    }

    private static String domainToAscii(String input) {
      try {
        return IDN.toASCII(input).toLowerCase(Locale.US);
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    private static int parsePort(String input, int pos, int limit) {
      try {
        // Canonicalize the port string to skip '\n' etc.
        String portString = canonicalize(input, pos, limit, "", false, false);
        int i = Integer.parseInt(portString);
        if (i > 0 && i <= 65535) return i;
        return -1;
      } catch (NumberFormatException e) {
        return -1; // Invalid port.
      }
    }
  }

  static String percentDecode(String encoded, boolean query) {
    return percentDecode(encoded, 0, encoded.length(), query);
  }

  static String percentDecode(String encoded, int pos, int limit, boolean query) {
    for (int i = pos; i < limit; i++) {
      char c = encoded.charAt(i);
      if (c == '%' || (c == '+' && query)) {
        // Slow path: the character at i requires decoding!
        Buffer out = new Buffer();
        out.writeUtf8(encoded, pos, i);
        percentDecode(out, encoded, i, limit, query);
        return out.readUtf8();
      }
    }

    // Fast path: no characters in [pos..limit) required decoding.
    return encoded.substring(pos, limit);
  }

  static void percentDecode(Buffer out, String encoded, int pos, int limit, boolean query) {
    int codePoint;
    for (int i = pos; i < limit; i += Character.charCount(codePoint)) {
      codePoint = encoded.codePointAt(i);
      if (codePoint == '%' && i + 2 < limit) {
        int d1 = decodeHexDigit(encoded.charAt(i + 1));
        int d2 = decodeHexDigit(encoded.charAt(i + 2));
        if (d1 != -1 && d2 != -1) {
          out.writeByte((d1 << 4) + d2);
          i += 2;
          continue;
        }
      } else if (codePoint == '+' && query) {
        out.writeByte(' ');
        continue;
      }
      out.writeUtf8CodePoint(codePoint);
    }
  }

  static int decodeHexDigit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
  }

  /**
   * Returns a substring of {@code input} on the range {@code [pos..limit)} with the following
   * transformations:
   * <ul>
   *   <li>Tabs, newlines, form feeds and carriage returns are skipped.
   *   <li>In queries, ' ' is encoded to '+' and '+' is encoded to "%2B".
   *   <li>Characters in {@code encodeSet} are percent-encoded.
   *   <li>Control characters and non-ASCII characters are percent-encoded.
   *   <li>All other characters are copied without transformation.
   * </ul>
   *
   * @param alreadyEncoded true to leave '%' as-is; false to convert it to '%25'.
   * @param query true if to encode ' ' as '+', and '+' as "%2B".
   */
  static String canonicalize(String input, int pos, int limit, String encodeSet,
      boolean alreadyEncoded, boolean query) {
    int codePoint;
    for (int i = pos; i < limit; i += Character.charCount(codePoint)) {
      codePoint = input.codePointAt(i);
      if (codePoint < 0x20
          || codePoint >= 0x7f
          || encodeSet.indexOf(codePoint) != -1
          || (codePoint == '%' && !alreadyEncoded)
          || (query && codePoint == '+')
          || (query && codePoint == ' ')) {
        // Slow path: the character at i requires encoding!
        StringBuilder out = new StringBuilder();
        out.append(input, pos, i);
        canonicalize(out, input, i, limit, encodeSet, alreadyEncoded, query);
        return out.toString();
      }
    }

    // Fast path: no characters in [pos..limit) required encoding.
    return input.substring(pos, limit);
  }

  static void canonicalize(StringBuilder out, String input, int pos, int limit,
      String encodeSet, boolean alreadyEncoded, boolean query) {
    Buffer utf8Buffer = null; // Lazily allocated.
    int codePoint;
    for (int i = pos; i < limit; i += Character.charCount(codePoint)) {
      codePoint = input.codePointAt(i);
      if (codePoint == '\t'
          || codePoint == '\n'
          || codePoint == '\f'
          || codePoint == '\r') {
        // Skip this character.
      } else if (query && codePoint == '+') {
        // In queries, encode '+' to '%2B'.
        out.append(alreadyEncoded ? "+" : "%2B");
      } else if (query && codePoint == ' ') {
        // In queries, encode ' ' to ' '.
        out.append('+');
      } else if (codePoint < 0x20
          || codePoint >= 0x7f
          || encodeSet.indexOf(codePoint) != -1
          || (codePoint == '%' && !alreadyEncoded)) {
        // Percent encode this character.
        if (utf8Buffer == null) {
          utf8Buffer = new Buffer();
        }
        utf8Buffer.writeUtf8CodePoint(codePoint);
        while (!utf8Buffer.exhausted()) {
          int b = utf8Buffer.readByte() & 0xff;
          out.append('%');
          out.append(HEX_DIGITS[(b >> 4) & 0xf]);
          out.append(HEX_DIGITS[b & 0xf]);
        }
      } else {
        // This character doesn't need encoding. Just copy it over.
        out.append((char) codePoint);
      }
    }
  }

  static String canonicalize(
      String input, String encodeSet, boolean alreadyEncoded, boolean query) {
    return canonicalize(input, 0, input.length(), encodeSet, alreadyEncoded, query);
  }
}
