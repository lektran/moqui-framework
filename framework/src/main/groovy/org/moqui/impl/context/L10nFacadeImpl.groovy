/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.context

import groovy.transform.CompileStatic
import org.moqui.context.Cache
import org.moqui.context.L10nFacade
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityFind
import org.moqui.impl.StupidUtilities

import javax.xml.bind.DatatypeConverter
import java.text.NumberFormat
import java.sql.Date
import java.sql.Time
import java.sql.Timestamp
import org.apache.commons.validator.routines.BigDecimalValidator
import org.apache.commons.validator.routines.CalendarValidator

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
public class L10nFacadeImpl implements L10nFacade {
    protected final static Logger logger = LoggerFactory.getLogger(L10nFacadeImpl.class)

    final static BigDecimalValidator bigDecimalValidator = new BigDecimalValidator(false)
    final static CalendarValidator calendarValidator = new CalendarValidator()

    protected final ExecutionContextFactoryImpl ecfi
    protected final Cache l10nMessage

    L10nFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        l10nMessage = ecfi.getCacheFacade().getCache("l10n.message")
    }

    protected Locale getLocale() { return ecfi.getExecutionContext().getUser().getLocale() }
    protected TimeZone getTimeZone() { return ecfi.getExecutionContext().getUser().getTimeZone() }

    @Override
    String getLocalizedMessage(String original) { return localize(original) }
    @Override
    String localize(String original) { return localize(original, getLocale()) }
    @Override
    String localize(String original, Locale locale) {
        if (!original) return ""
        if (original.length() > 255) {
            throw new IllegalArgumentException("Original String cannot be more than 255 characters long, passed in string was [${original.length()}] characters long")
        }

        if (locale == null) locale = getLocale()
        String localeString = locale.toString()

        String cacheKey = original + "::" + localeString
        String lmsg = l10nMessage.get(cacheKey)
        if (lmsg != null) return lmsg

        String defaultValue = original
        int localeUnderscoreIndex = localeString.indexOf('_')

        EntityFind find = ecfi.getEntityFacade().find("moqui.basic.LocalizedMessage")
                .condition(["original":original, "locale":localeString] as Map<String, Object>).useCache(true)
        EntityValue localizedMessage = find.one()
        if (!localizedMessage && localeUnderscoreIndex > 0)
            localizedMessage = find.condition("locale", localeString.substring(0, localeUnderscoreIndex)).one()
        if (!localizedMessage)
            localizedMessage = find.condition("locale", "default").one()

        // if original has a hash and we still don't have a localizedMessage then use what precedes the hash and try again
        if (!localizedMessage) {
            int indexOfCloseCurly = original.lastIndexOf('}')
            int indexOfHash = original.lastIndexOf('##')
            if (indexOfHash > 0 && indexOfHash > indexOfCloseCurly) {
                defaultValue = original.substring(0, indexOfHash)
                EntityFind findHash = ecfi.getEntityFacade().find("moqui.basic.LocalizedMessage")
                        .condition(["original":defaultValue, "locale":localeString] as Map<String, Object>).useCache(true)
                localizedMessage = findHash.one()
                if (!localizedMessage && localeUnderscoreIndex > 0)
                    localizedMessage = findHash.condition("locale", localeString.substring(0, localeUnderscoreIndex)).one()
                if (!localizedMessage)
                    localizedMessage = findHash.condition("locale", "default").one()
            }
        }

        String result = localizedMessage != null ? localizedMessage.localized : defaultValue
        l10nMessage.put(cacheKey, result)
        return result
    }

    @Override
    String formatCurrency(Object amount, String uomId, Integer fractionDigits) {
        return formatCurrency(amount, uomId, fractionDigits, getLocale())
    }
    @Override
    String formatCurrency(Object amount, String uomId, Integer fractionDigits, Locale locale) {
        if (amount == null) return ""
        if (amount instanceof CharSequence) {
            if (amount.length() == 0) {
                return ""
            } else {
                amount = parseNumber((String) amount, null)
            }
        }

        if (fractionDigits == null) fractionDigits = 2
        if (locale == null) locale = getLocale()
        NumberFormat nf = NumberFormat.getCurrencyInstance(locale)
        if (uomId) nf.setCurrency(Currency.getInstance(uomId))
        nf.setMaximumFractionDigits(fractionDigits)
        nf.setMinimumFractionDigits(fractionDigits)
        String formattedAmount = nf.format(amount)
        return formattedAmount
    }

    @Override
    Time parseTime(String input, String format) {
        Locale curLocale = getLocale()
        TimeZone curTz = getTimeZone()
        if (!format) format = "HH:mm:ss.SSS"
        Calendar cal = calendarValidator.validate(input, format, curLocale, curTz)
        if (cal == null) cal = calendarValidator.validate(input, "HH:mm:ss", curLocale, curTz)
        if (cal == null) cal = calendarValidator.validate(input, "HH:mm", curLocale, curTz)
        if (cal == null) cal = calendarValidator.validate(input, "h:mm a", curLocale, curTz)
        if (cal == null) cal = calendarValidator.validate(input, "h:mm:ss a", curLocale, curTz)
        // also try the full ISO-8601, times may come in that way (even if funny with a date of 1970-01-01)
        if (cal == null) cal = calendarValidator.validate(input, "yyyy-MM-dd'T'HH:mm:ssZ", curLocale, curTz)
        if (cal != null) {
            Time time = new Time(cal.getTimeInMillis())
            // logger.warn("============== parseTime input=${input} cal=${cal} long=${cal.getTimeInMillis()} time=${time} time long=${time.getTime()} util date=${new java.util.Date(cal.getTimeInMillis())} timestamp=${new java.sql.Timestamp(cal.getTimeInMillis())}")
            return time
        }

        // try interpreting the String as a long
        try {
            Long lng = Long.valueOf(input)
            return new Time(lng)
        } catch (NumberFormatException e) {
            if (logger.isTraceEnabled()) logger.trace("Ignoring NumberFormatException for Time parse: ${e.toString()}")
        }

        return null
    }
    static String formatTime(Time input, String format, Locale locale, TimeZone tz) {
        if (!format) format = "HH:mm:ss"
        String timeStr = calendarValidator.format(input, format, locale, tz)
        // logger.warn("============= formatTime input=${input} timeStr=${timeStr} long=${input.getTime()}")
        return timeStr
    }

    @Override
    Date parseDate(String input, String format) {
        if (!format) format = "yyyy-MM-dd"
        Locale curLocale = getLocale()

        // NOTE DEJ 20150317 Date parsing in terms of time zone causes funny issues because the time part of the long
        //   since epoch representation is lost going to/from the DB, especially since the time portion is set to 0 and
        //   with time zone conversion when the system date is in an earlier time zone than the user date it pushes the
        //   Date to the previous day; what seems like the best solution is to parse and save the Date in the
        //   system/default time zone, and format it that way as well.
        // The BIG dilemma is there is no way to represent a Date (yyyy-MM-dd) in an object that does not use the long
        //   since epoch but rather is an absolute year, month, and day... which is really what we want.
        /*
        TimeZone curTz = getTimeZone()
        Calendar cal = calendarValidator.validate(input, format, curLocale, curTz)
        if (cal == null) cal = calendarValidator.validate(input, "MM/dd/yyyy", curLocale, curTz)
        // also try the full ISO-8601, dates may come in that way
        if (cal == null) cal = calendarValidator.validate(input, "yyyy-MM-dd'T'HH:mm:ssZ", curLocale, curTz)
        */

        Calendar cal = calendarValidator.validate(input, format, curLocale)
        if (cal == null) cal = calendarValidator.validate(input, "MM/dd/yyyy", curLocale)
        // also try the full ISO-8601, dates may come in that way
        if (cal == null) cal = calendarValidator.validate(input, "yyyy-MM-dd'T'HH:mm:ssZ", curLocale)
        if (cal != null) {
            Date date = new Date(cal.getTimeInMillis())
            // logger.warn("============== parseDate input=${input} cal=${cal} long=${cal.getTimeInMillis()} date=${date} date long=${date.getTime()} util date=${new java.util.Date(cal.getTimeInMillis())} timestamp=${new java.sql.Timestamp(cal.getTimeInMillis())}")
            return date
        }

        // try interpreting the String as a long
        try {
            Long lng = Long.valueOf(input)
            return new Date(lng)
        } catch (NumberFormatException e) {
            if (logger.isTraceEnabled()) logger.trace("Ignoring NumberFormatException for Date parse: ${e.toString()}")
        }

        return null
    }
    static String formatDate(Date input, String format, Locale locale, TimeZone tz) {
        if (!format) format = "yyyy-MM-dd"
        // See comment in parseDate for why we are ignoring the time zone
        // String dateStr = calendarValidator.format(input, format, getLocale(), getTimeZone())
        String dateStr = calendarValidator.format(input, format, locale)
        // logger.warn("============= formatDate input=${input} dateStr=${dateStr} long=${input.getTime()}")
        return dateStr
    }

    static final List<String> timestampFormats = ["yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "yyyy-MM-dd HH:mm:ss.SSS z"]

    @Override
    Timestamp parseTimestamp(String input, String format) {
        if (!input) return null
        Locale curLocale = getLocale()
        TimeZone curTz = getTimeZone()
        Calendar cal
        if (format) cal = calendarValidator.validate(input, format, curLocale, curTz)

        // long values are pretty common, so if there are no special characters try that first (fast to check)
        if (cal == null) {
            int nonDigits = StupidUtilities.countChars(input, false, true, true)
            if (nonDigits == 0) {
                try {
                    Long lng = Long.valueOf(input)
                    return new Timestamp(lng)
                } catch (NumberFormatException e) {
                    if (logger.isTraceEnabled()) logger.trace("Ignoring NumberFormatException for Timestamp parse: ${e.toString()}")
                }
            }
        }

        // try a bunch of other format strings
        if (cal == null) {
            Iterator timestampFormatIter = timestampFormats.iterator()
            while (cal == null && timestampFormatIter.hasNext()) {
                String tf = timestampFormatIter.next()
                cal = calendarValidator.validate(input, tf, curLocale, curTz)
            }
        }

        // logger.warn("=========== input=${input}, cal=${cal}, long=${cal?.getTimeInMillis()}, locale=${curLocale}, timeZone=${curTz}, System=${System.currentTimeMillis()}")
        if (cal != null) return new Timestamp(cal.getTimeInMillis())

        try {
            // NOTE: do this AFTER the long parse because long numbers are interpreted really weird by this
            // ISO 8601 parsing using JAXB DatatypeConverter.parseDateTime(); on Java 7 can use "X" instead of "Z" in format string, but not in Java 6
            cal = DatatypeConverter.parseDateTime(input)
            if (cal != null) return new Timestamp(cal.getTimeInMillis())
        } catch (Exception e) {
            if (logger.isTraceEnabled()) logger.trace("Ignoring Exception for DatatypeConverter Timestamp parse: ${e.toString()}")
        }

        return null
    }
    static String formatTimestamp(Timestamp input, String format, Locale locale, TimeZone tz) {
        if (!format) format = "yyyy-MM-dd HH:mm"
        return calendarValidator.format(input, format, locale, tz)
    }

    @Override
    Calendar parseDateTime(String input, String format) {
        return calendarValidator.validate(input, format, getLocale(), getTimeZone())
    }
    static String formatDateTime(Calendar input, String format, Locale locale, TimeZone tz) {
        return calendarValidator.format(input, format, locale, tz)
    }

    @Override
    BigDecimal parseNumber(String input, String format) {
        return bigDecimalValidator.validate(input, format, getLocale())
    }
    static String formatNumber(Number input, String format, Locale locale) {
        return bigDecimalValidator.format(input, format, locale)
    }

    @Override
    String formatValue(Object value, String fmt) { return format(value, fmt) }
    @Override
    String format(Object value, String format) {
        return this.format(value, format, getLocale(), getTimeZone())
    }
    @Override
    String format(Object value, String format, Locale locale, TimeZone tz) {
        if (locale == null) locale = getLocale()
        if (tz == null) tz = getTimeZone()
        if (value == null) return ""
        Class valueClass = value.getClass()
        if (valueClass == String.class) return value
        if (valueClass == Timestamp.class) return formatTimestamp((Timestamp) value, format, locale, tz)
        if (valueClass == Date.class) return formatDate((Date) value, format, locale, tz)
        if (valueClass == Time.class) return formatTime((Time) value, format, locale, tz)
        // Calendar is an abstract class, so must use instanceof here as well
        if (value instanceof Calendar) return formatDateTime((Calendar) value, format, locale, tz)
        // this one needs to be instanceof to include the many sub-classes of Number
        if (value instanceof Number) return formatNumber(value, format, locale)
        return value as String
    }
}
