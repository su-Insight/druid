/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
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
package com.alibaba.druid.util;

import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * @author sandzhang[sandzhangtoo@gmail.com]
 */
public class StringUtils {
    private static final Log LOG = LogFactory.getLog(StringUtils.class);

    private StringUtils() {
    }

    /**
     * Example: subString("12345","1","4")=23
     *
     * @param src src
     * @param start start
     * @param to to
     * @return integer of substring
     */
    public static Integer subStringToInteger(String src, String start, String to) {
        return stringToInteger(subString(src, start, to));
    }

    /**
     * Example: subString("abcd","a","c")="b"
     *
     * @param src src
     * @param start null while start from index=0
     * @param to    null while to index=src.length
     * @return substring
     */
    public static String subString(String src, String start, String to) {
        return subString(src, start, to, false);
    }

    /**
     * Example: subString("abcdc","a","c",true)="bcd"
     *
     * @param src src
     * @param start  null while start from index=0
     * @param to     null while to index=src.length
     * @param toLast true while to index=src.lastIndexOf(to)
     * @return subString
     */
    public static String subString(String src, String start, String to, boolean toLast) {
        int indexFrom = start == null ? 0 : src.indexOf(start);
        int indexTo;
        if (to == null) {
            indexTo = src.length();
        } else {
            indexTo = toLast ? src.lastIndexOf(to) : src.indexOf(to);
        }
        if (indexFrom < 0 || indexTo < 0 || indexFrom > indexTo) {
            return null;
        }

        if (null != start) {
            indexFrom += start.length();
        }

        return src.substring(indexFrom, indexTo);
    }

    /**
     * @param in in
     * @return integer of string
     */
    public static Integer stringToInteger(String in) {
        if (in == null) {
            return null;
        }
        in = in.trim();
        if (in.length() == 0) {
            return null;
        }

        try {
            return Integer.parseInt(in);
        } catch (NumberFormatException e) {
            LOG.warn("stringToInteger fail,string=" + in, e);
            return null;
        }
    }

    public static boolean equals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    public static boolean equalsIgnoreCase(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equalsIgnoreCase(b);
    }

    public static boolean isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }

    public static int lowerHashCode(String text) {
        if (text == null) {
            return 0;
        }
//        return text.toLowerCase().hashCode();
        int h = 0;
        for (int i = 0; i < text.length(); ++i) {
            char ch = text.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                ch = (char) (ch + 32);
            }

            h = 31 * h + ch;
        }
        return h;
    }

    public static boolean isNumber(String str) {
        if (str == null || str.length() == 0) {
            return false;
        }
        int sz = str.length();
        boolean hasExp = false;
        boolean hasDecPoint = false;
        boolean allowSigns = false;
        boolean foundDigit = false;
        // deal with any possible sign up front
        int start = (str.charAt(0) == '-') ? 1 : 0;
        if (sz > start + 1) {
            if (str.charAt(start) == '0' && str.charAt(start + 1) == 'x') {
                int i = start + 2;
                if (i == sz) {
                    return false; // str == "0x"
                }
                // checking hex (it can't be anything else)
                for (; i < str.length(); i++) {
                    char ch = str.charAt(i);
                    if ((ch < '0' || ch > '9')
                            && (ch < 'a' || ch > 'f')
                            && (ch < 'A' || ch > 'F')) {
                        return false;
                    }
                }
                return true;
            }
        }
        sz--; // don't want to loop to the last char, check it afterwords
        // for type qualifiers
        int i = start;
        // loop to the next to last char or to the last char if we need another digit to
        // make a valid number (e.g. chars[0..5] = "1234E")
        while (i < sz || (i < sz + 1 && allowSigns && !foundDigit)) {
            char ch = str.charAt(i);
            if (ch >= '0' && ch <= '9') {
                foundDigit = true;
                allowSigns = false;

            } else if (ch == '.') {
                if (hasDecPoint || hasExp) {
                    // two decimal points or dec in exponent
                    return false;
                }
                hasDecPoint = true;
            } else if (ch == 'e' || ch == 'E') {
                // we've already taken care of hex.
                if (hasExp) {
                    // two E's
                    return false;
                }
                if (!foundDigit) {
                    return false;
                }
                hasExp = true;
                allowSigns = true;
            } else if (ch == '+' || ch == '-') {
                if (!allowSigns) {
                    return false;
                }
                allowSigns = false;
                foundDigit = false; // we need a digit after the E
            } else {
                return false;
            }
            i++;
        }
        if (i < str.length()) {
            char ch = str.charAt(i);

            if (ch >= '0' && ch <= '9') {
                // no type qualifier, OK
                return true;
            }
            if (ch == 'e' || ch == 'E') {
                // can't have an E at the last byte
                return false;
            }
            if (!allowSigns
                    && (ch == 'd'
                    || ch == 'D'
                    || ch == 'f'
                    || ch == 'F')) {
                return foundDigit;
            }
            if (ch == 'l'
                    || ch == 'L') {
                // not allowing L with an exponent
                return foundDigit && !hasExp;
            }
            // last character is illegal
            return false;
        }
        // allowSigns is true iff the val ends in 'E'
        // found digit it to make sure weird stuff like '.' and '1E-' doesn't pass
        return !allowSigns && foundDigit;
    }

    public static boolean isNumber(char[] chars) {
        if (chars == null || chars.length == 0) {
            return false;
        }
        int sz = chars.length;
        boolean hasExp = false;
        boolean hasDecPoint = false;
        boolean allowSigns = false;
        boolean foundDigit = false;
        // deal with any possible sign up front
        int start = (chars[0] == '-') ? 1 : 0;
        if (sz > start + 1) {
            if (chars[start] == '0' && chars[start + 1] == 'x') {
                int i = start + 2;
                if (i == sz) {
                    return false; // str == "0x"
                }
                // checking hex (it can't be anything else)
                for (; i < chars.length; i++) {
                    char ch = chars[i];
                    if ((ch < '0' || ch > '9')
                            && (ch < 'a' || ch > 'f')
                            && (ch < 'A' || ch > 'F')) {
                        return false;
                    }
                }
                return true;
            }
        }
        sz--; // don't want to loop to the last char, check it afterwords
        // for type qualifiers
        int i = start;
        // loop to the next to last char or to the last char if we need another digit to
        // make a valid number (e.g. chars[0..5] = "1234E")
        while (i < sz || (i < sz + 1 && allowSigns && !foundDigit)) {
            char ch = chars[i];
            if (ch >= '0' && ch <= '9') {
                foundDigit = true;
                allowSigns = false;

            } else if (ch == '.') {
                if (hasDecPoint || hasExp) {
                    // two decimal points or dec in exponent
                    return false;
                }
                hasDecPoint = true;
            } else if (ch == 'e' || ch == 'E') {
                // we've already taken care of hex.
                if (hasExp) {
                    // two E's
                    return false;
                }
                if (!foundDigit) {
                    return false;
                }
                hasExp = true;
                allowSigns = true;
            } else if (ch == '+' || ch == '-') {
                if (!allowSigns) {
                    return false;
                }
                allowSigns = false;
                foundDigit = false; // we need a digit after the E
            } else {
                return false;
            }
            i++;
        }
        if (i < chars.length) {
            char ch = chars[i];
            if (ch >= '0' && ch <= '9') {
                // no type qualifier, OK
                return true;
            }
            if (ch == 'e' || ch == 'E') {
                // can't have an E at the last byte
                return false;
            }
            if (!allowSigns
                    && (ch == 'd'
                    || ch == 'D'
                    || ch == 'f'
                    || ch == 'F')) {
                return foundDigit;
            }
            if (ch == 'l'
                    || ch == 'L') {
                // not allowing L with an exponent
                return foundDigit && !hasExp;
            }

            if (ch == '.') {
                return true;
            }
            // last character is illegal
            return false;
        }
        // allowSigns is true iff the val ends in 'E'
        // found digit it to make sure weird stuff like '.' and '1E-' doesn't pass
        return !allowSigns && foundDigit;
    }

    public static String formatDateTime19(long millis, TimeZone timeZone) {
        Calendar cale = timeZone == null
                ? Calendar.getInstance()
                : Calendar.getInstance(timeZone);
        cale.setTimeInMillis(millis);

        int year = cale.get(Calendar.YEAR);
        int month = cale.get(Calendar.MONTH) + 1;
        int dayOfMonth = cale.get(Calendar.DAY_OF_MONTH);
        int hour = cale.get(Calendar.HOUR_OF_DAY);
        int minute = cale.get(Calendar.MINUTE);
        int second = cale.get(Calendar.SECOND);

        char[] chars = new char[19];
        chars[0] = (char) (year / 1000 + '0');
        chars[1] = (char) ((year / 100) % 10 + '0');
        chars[2] = (char) ((year / 10) % 10 + '0');
        chars[3] = (char) (year % 10 + '0');
        chars[4] = '-';
        chars[5] = (char) (month / 10 + '0');
        chars[6] = (char) (month % 10 + '0');
        chars[7] = '-';
        chars[8] = (char) (dayOfMonth / 10 + '0');
        chars[9] = (char) (dayOfMonth % 10 + '0');
        chars[10] = ' ';
        chars[11] = (char) (hour / 10 + '0');
        chars[12] = (char) (hour % 10 + '0');
        chars[13] = ':';
        chars[14] = (char) (minute / 10 + '0');
        chars[15] = (char) (minute % 10 + '0');
        chars[16] = ':';
        chars[17] = (char) (second / 10 + '0');
        chars[18] = (char) (second % 10 + '0');
        return new String(chars);
    }

    public static String removeNameQuotes(String s) {
        if (s == null || s.length() <= 1) {
            return null;
        }
        int len = s.length();
        char c0 = s.charAt(0);
        char last = s.charAt(len - 1);

        if (c0 == last && (c0 == '`' || c0 == '\'' || c0 == '\"')) {
            return s.substring(1, len - 1);
        }
        return s;
    }

    public static String replaceAll(String s, String src, String dest) {
        if (s == null || src == null || dest == null || src.length() == 0) {
            return s;
        }
        int pos = s.indexOf(src);
        if (pos < 0) {
            return s;
        }
        int capacity = dest.length() > src.length() ? s.length() * 2 : s.length();
        StringBuilder sb = new StringBuilder(capacity);
        int writen = 0;
        for (; pos >= 0; ) {
            sb.append(s, writen, pos);
            sb.append(dest);
            writen = pos + src.length();
            pos = s.indexOf(src, writen);
        }
        sb.append(s, writen, s.length());
        return sb.toString();
    }
}
