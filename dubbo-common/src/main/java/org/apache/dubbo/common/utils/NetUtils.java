/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.utils;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * IP and Port Helper for RPC
 */
public class NetUtils {
    private static final Logger logger = LoggerFactory.getLogger(NetUtils.class);

    // returned port range is [30000, 39999]
    private static final int RND_PORT_START = 30000;
    private static final int RND_PORT_RANGE = 10000;

    // valid port range is (0, 65535]
    private static final int MIN_PORT = 0;
    private static final int MAX_PORT = 65535;

    private static final Pattern ADDRESS_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}\\:\\d{1,5}$");
    private static final Pattern LOCAL_IP_PATTERN = Pattern.compile("127(\\.\\d{1,3}){3}$");
    private static final Pattern IP_PATTERN = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3,5}$");

    private static final Map<String, String> hostNameCache = new LRUCache<>(1000);
    private static volatile InetAddress LOCAL_ADDRESS = null;

    private static final String SPLIT_IPV4_CHARECTER = "\\.";
    private static final String SPLIT_IPV6_CHARECTER = ":";

    public static int getRandomPort() {
        return RND_PORT_START + ThreadLocalRandom.current().nextInt(RND_PORT_RANGE);
    }

    public static int getAvailablePort() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(null);
            return ss.getLocalPort();
        } catch (IOException e) {
            return getRandomPort();
        }
    }

    public static int getAvailablePort(int port) {
        if (port <= 0) {
            return getAvailablePort();
        }
        for (int i = port; i < MAX_PORT; i++) {
            try (ServerSocket ss = new ServerSocket(i)) {
                return i;
            } catch (IOException e) {
                // continue
            }
        }
        return port;
    }

    public static boolean isInvalidPort(int port) {
        return port <= MIN_PORT || port > MAX_PORT;
    }

    public static boolean isValidAddress(String address) {
        return ADDRESS_PATTERN.matcher(address).matches();
    }

    public static boolean isLocalHost(String host) {
        return host != null
                && (LOCAL_IP_PATTERN.matcher(host).matches()
                || host.equalsIgnoreCase(Constants.LOCALHOST_KEY));
    }

    public static boolean isAnyHost(String host) {
        return Constants.ANYHOST_VALUE.equals(host);
    }

    // host是否无效或者为本地地址
    // FIXME: should remove this method completely
    public static boolean isInvalidLocalHost(String host) {
        return host == null
                || host.length() == 0
                || host.equalsIgnoreCase(Constants.LOCALHOST_KEY)
                || host.equals(Constants.ANYHOST_VALUE)
                || (LOCAL_IP_PATTERN.matcher(host).matches());
    }

    // FIXME: should remove this method completely
    public static boolean isValidLocalHost(String host) {
        return !isInvalidLocalHost(host);
    }

    public static InetSocketAddress getLocalSocketAddress(String host, int port) {
        return isInvalidLocalHost(host) ?
                new InetSocketAddress(port) : new InetSocketAddress(host, port);
    }

    static boolean isValidV4Address(InetAddress address) {
        String name = address.getHostAddress();
        return (name != null
                && IP_PATTERN.matcher(name).matches()
                && !Constants.ANYHOST_VALUE.equals(name)
                && !Constants.LOCALHOST_VALUE.equals(name));
    }

    /**
     * Check if an ipv6 address is reachable.
     *
     * @param address the given address
     * @return true if it is reachable
     */
    static boolean isValidV6Address(Inet6Address address) {
        boolean preferIpv6 = Boolean.getBoolean("java.net.preferIPv6Addresses");
        if (!preferIpv6) {
            return false;
        }
        try {
            return address.isReachable(100);
        } catch (IOException e) {
            // ignore
        }
        return false;
    }

    static boolean isValidPublicAddress(InetAddress address) {
        return !address.isSiteLocalAddress() && !address.isLoopbackAddress();
    }

    /**
     * normalize the ipv6 Address, convert scope name to scope id.
     * e.g.
     * convert
     * fe80:0:0:0:894:aeec:f37d:23e1%en0
     * to
     * fe80:0:0:0:894:aeec:f37d:23e1%5
     * <p>
     * The %5 after ipv6 address is called scope id.
     * see java doc of {@link Inet6Address} for more details.
     *
     * @param address the input address
     * @return the normalized address, with scope id converted to int
     */
    static InetAddress normalizeV6Address(Inet6Address address) {
        String addr = address.getHostAddress();
        int i = addr.lastIndexOf('%');
        if (i > 0) {
            try {
                return InetAddress.getByName(addr.substring(0, i) + '%' + address.getScopeId());
            } catch (UnknownHostException e) {
                // ignore
                logger.debug("Unknown IPV6 address: ", e);
            }
        }
        return address;
    }

    public static String getLocalHost() {
        InetAddress address = getLocalAddress();
        return address == null ? Constants.LOCALHOST_VALUE : address.getHostAddress();
    }

    // 解析host，如果 host 解析出来的 主机名无效，则使用本地地址
    public static String filterLocalHost(String host) {
        if (host == null || host.length() == 0) {
            return host;
        }
        if (host.contains("://")) {
            URL u = URL.valueOf(host);
            if (NetUtils.isInvalidLocalHost(u.getHost())) {
                //如果 host 解析出来的 主机名无效，则使用本地地址
                return u.setHost(NetUtils.getLocalHost()).toFullString();
            }
        } else if (host.contains(":")) {
            int i = host.lastIndexOf(':');
            if (NetUtils.isInvalidLocalHost(host.substring(0, i))) {
                //如果 host 解析出来的 主机名无效，则使用本地地址
                return NetUtils.getLocalHost() + host.substring(i);
            }
        } else {
            if (NetUtils.isInvalidLocalHost(host)) {
                //如果 host 解析出来的 主机名无效，则使用本地地址
                return NetUtils.getLocalHost();
            }
        }
        return host;
    }

    public static String getIpByConfig() {
        String configIp = ConfigurationUtils.getProperty(Constants.DUBBO_IP_TO_BIND);
        if (configIp != null) {
            return configIp;
        }

        return getIpByHost(getLocalAddress().getHostName());
    }

    /**
     * Find first valid IP from local network card
     *
     * @return first valid local IP
     */
    public static InetAddress getLocalAddress() {
        if (LOCAL_ADDRESS != null) {
            return LOCAL_ADDRESS;
        }
        InetAddress localAddress = getLocalAddress0();
        LOCAL_ADDRESS = localAddress;
        return localAddress;
    }

    private static Optional<InetAddress> toValidAddress(InetAddress address) {
        if (isValidPublicAddress(address)) {
            if (address instanceof Inet6Address) {
                Inet6Address v6Address = (Inet6Address) address;
                if (isValidV6Address(v6Address)) {
                    return Optional.ofNullable(normalizeV6Address(v6Address));
                }
            }
            if (isValidV4Address(address)) {
                return Optional.of(address);
            }
        }
        return Optional.empty();
    }

    private static InetAddress getLocalAddress0() {
        InetAddress localAddress = null;
        try {
            localAddress = InetAddress.getLocalHost();
            Optional<InetAddress> addressOp = toValidAddress(localAddress);
            if (addressOp.isPresent()) {
                return addressOp.get();
            }
        } catch (Throwable e) {
            logger.warn(e);
        }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (null == interfaces) {
                return localAddress;
            }
            while (interfaces.hasMoreElements()) {
                try {
                    NetworkInterface network = interfaces.nextElement();
                    Enumeration<InetAddress> addresses = network.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        try {
                            Optional<InetAddress> addressOp = toValidAddress(addresses.nextElement());
                            if (addressOp.isPresent()) {
                                return addressOp.get();
                            }
                        } catch (Throwable e) {
                            logger.warn(e);
                        }
                    }
                } catch (Throwable e) {
                    logger.warn(e);
                }
            }
        } catch (Throwable e) {
            logger.warn(e);
        }
        return localAddress;
    }

    public static String getHostName(String address) {
        try {
            int i = address.indexOf(':');
            if (i > -1) {
                address = address.substring(0, i);
            }
            String hostname = hostNameCache.get(address);
            if (hostname != null && hostname.length() > 0) {
                return hostname;
            }
            InetAddress inetAddress = InetAddress.getByName(address);
            if (inetAddress != null) {
                hostname = inetAddress.getHostName();
                hostNameCache.put(address, hostname);
                return hostname;
            }
        } catch (Throwable e) {
            // ignore
        }
        return address;
    }

    /**
     * @param hostName
     * @return ip address or hostName if UnknownHostException
     */
    public static String getIpByHost(String hostName) {
        try {
            return InetAddress.getByName(hostName).getHostAddress();
        } catch (UnknownHostException e) {
            return hostName;
        }
    }

    public static String toAddressString(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    public static InetSocketAddress toAddress(String address) {
        int i = address.indexOf(':');
        String host;
        int port;
        if (i > -1) {
            host = address.substring(0, i);
            port = Integer.parseInt(address.substring(i + 1));
        } else {
            host = address;
            port = 0;
        }
        return new InetSocketAddress(host, port);
    }

    public static String toURL(String protocol, String host, int port, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append(protocol).append("://");
        sb.append(host).append(':').append(port);
        if (path.charAt(0) != '/') {
            sb.append('/');
        }
        sb.append(path);
        return sb.toString();
    }

    public static void joinMulticastGroup(MulticastSocket multicastSocket, InetAddress multicastAddress) throws IOException {
        setInterface(multicastSocket, multicastAddress instanceof Inet6Address);
        multicastSocket.setLoopbackMode(false);
        multicastSocket.joinGroup(multicastAddress);
    }

    public static void setInterface(MulticastSocket multicastSocket, boolean preferIpv6) throws IOException {
        boolean interfaceSet = false;
        Enumeration interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface i = (NetworkInterface) interfaces.nextElement();
            Enumeration addresses = i.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = (InetAddress) addresses.nextElement();
                if (preferIpv6 && address instanceof Inet6Address) {
                    multicastSocket.setInterface(address);
                    interfaceSet = true;
                    break;
                } else if (!preferIpv6 && address instanceof Inet4Address) {
                    multicastSocket.setInterface(address);
                    interfaceSet = true;
                    break;
                }
            }
            if (interfaceSet) {
                break;
            }
        }
    }

    public static boolean matchIpExpression(String pattern, String host, int port) throws UnknownHostException {

        // if the pattern is subnet format, it will not be allowed to config port param in pattern.
        if (pattern.contains("/")) {
            CIDRUtils utils = new CIDRUtils(pattern);
            return utils.isInRange(host);
        }


        return matchIpRange(pattern, host, port);
    }

    /**
     * @param pattern
     * @param host
     * @param port
     * @return
     * @throws UnknownHostException
     */
    public static boolean matchIpRange(String pattern, String host, int port) throws UnknownHostException {
        if (pattern == null || host == null) {
            throw new IllegalArgumentException("Illegal Argument pattern or hostName. Pattern:" + pattern + ", Host:" + host);
        }
        pattern = pattern.trim();
        if (pattern.equals("*.*.*.*") || pattern.equals("*")) {
            return true;
        }

        InetAddress inetAddress = InetAddress.getByName(host);
        boolean isIpv4 = isValidV4Address(inetAddress) ? true : false;
        String[] hostAndPort = getPatternHostAndPort(pattern, isIpv4);
        if (hostAndPort[1] != null && !hostAndPort[1].equals(String.valueOf(port))) {
            return false;
        }
        pattern = hostAndPort[0];

        String splitCharacter = SPLIT_IPV4_CHARECTER;
        if (!isIpv4) {
            splitCharacter = SPLIT_IPV6_CHARECTER;
        }
        String[] mask = pattern.split(splitCharacter);
        //check format of pattern
        checkHostPattern(pattern, mask, isIpv4);

        host = inetAddress.getHostAddress();

        String[] ip_address = host.split(splitCharacter);
        if (pattern.equals(host)) {
            return true;
        }
        // short name condition
        if (!ipPatternContainExpression(pattern)) {
            InetAddress patternAddress = InetAddress.getByName(pattern);
            if (patternAddress.getHostAddress().equals(host)) {
                return true;
            } else {
                return false;
            }
        }
        for (int i = 0; i < mask.length; i++) {
            if (mask[i].equals("*") || mask[i].equals(ip_address[i])) {
                continue;
            } else if (mask[i].contains("-")) {
                String[] rangeNumStrs = mask[i].split("-");
                if (rangeNumStrs.length != 2) {
                    throw new IllegalArgumentException("There is wrong format of ip Address: " + mask[i]);
                }
                Integer min = getNumOfIpSegment(rangeNumStrs[0], isIpv4);
                Integer max = getNumOfIpSegment(rangeNumStrs[1], isIpv4);
                Integer ip = getNumOfIpSegment(ip_address[i], isIpv4);
                if (ip < min || ip > max) {
                    return false;
                }
            } else if ("0".equals(ip_address[i]) && ("0".equals(mask[i]) || "00".equals(mask[i]) || "000".equals(mask[i]) || "0000".equals(mask[i]))) {
                continue;
            } else if (!mask[i].equals(ip_address[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean ipPatternContainExpression(String pattern) {
        return pattern.contains("*") || pattern.contains("-");
    }

    private static void checkHostPattern(String pattern, String[] mask, boolean isIpv4) {
        if (!isIpv4) {
            if (mask.length != 8 && ipPatternContainExpression(pattern)) {
                throw new IllegalArgumentException("If you config ip expression that contains '*' or '-', please fill qulified ip pattern like 234e:0:4567:0:0:0:3d:*. ");
            }
            if (mask.length != 8 && !pattern.contains("::")) {
                throw new IllegalArgumentException("The host is ipv6, but the pattern is not ipv6 pattern : " + pattern);
            }
        } else {
            if (mask.length != 4) {
                throw new IllegalArgumentException("The host is ipv4, but the pattern is not ipv4 pattern : " + pattern);
            }
        }
    }

    private static String[] getPatternHostAndPort(String pattern, boolean isIpv4) {
        String[] result = new String[2];
        if (pattern.startsWith("[") && pattern.contains("]:")) {
            int end = pattern.indexOf("]:");
            result[0] = pattern.substring(1, end);
            result[1] = pattern.substring(end + 2);
            return result;
        } else if (pattern.startsWith("[") && pattern.endsWith("]")) {
            result[0] = pattern.substring(1, pattern.length() - 1);
            result[1] = null;
            return result;
        } else if (isIpv4 && pattern.contains(":")) {
            int end = pattern.indexOf(":");
            result[0] = pattern.substring(0, end);
            result[1] = pattern.substring(end + 1);
            return result;
        } else {
            result[0] = pattern;
            return result;
        }
    }

    private static Integer getNumOfIpSegment(String ipSegment, boolean isIpv4) {
        if (isIpv4) {
            return Integer.parseInt(ipSegment);
        }
        return Integer.parseInt(ipSegment, 16);
    }

}
