package io.github.essandhu.ledger.console.web;

import java.util.Currency;

import io.github.essandhu.ledger.console.api.LedgerApi.Money;

/**
 * Minor units → display string. This is where the core's deliberate exponent-blindness ends
 * (its Money javadoc: "the exponent is presentation owned by clients") — the console owns it,
 * closing the ADR-0001 loop: JPY 0, EUR 2, BHD 3, straight from the JDK's ISO-4217 table.
 *
 * <p>Pure string/long arithmetic, no floating point (the core bans float/double outright;
 * the console honors the spirit) and no locale-dependent formatter (CLDR separator drift
 * across JDK upgrades would break golden-string tests): fixed en-US style — comma grouping,
 * dot decimal. The string route also survives {@code Long.MIN_VALUE}, which
 * {@code Math.abs}-based formatting silently corrupts.
 *
 * <p>Unknown codes and pseudo-currencies (exponent −1) fall back to raw minor units — the
 * core rejects both at account creation, so the guard is defensive, not reachable via the
 * ledger's own data.
 */
final class MoneyFormat {

    private MoneyFormat() {
    }

    static String format(Money money) {
        return format(money.amount(), money.currency());
    }

    static String format(long minorUnits, String currencyCode) {
        int exponent = exponentOf(currencyCode);
        if (exponent < 0) {
            return minorUnits + " " + currencyCode + " (minor units)";
        }
        String digits = Long.toString(minorUnits);
        boolean negative = digits.startsWith("-");
        if (negative) {
            digits = digits.substring(1);
        }
        String integerPart;
        String fractionPart;
        if (exponent == 0) {
            integerPart = digits;
            fractionPart = "";
        } else {
            if (digits.length() <= exponent) {
                digits = "0".repeat(exponent + 1 - digits.length()) + digits;
            }
            int split = digits.length() - exponent;
            integerPart = digits.substring(0, split);
            fractionPart = "." + digits.substring(split);
        }
        return (negative ? "-" : "") + grouped(integerPart) + fractionPart + " " + currencyCode;
    }

    private static int exponentOf(String currencyCode) {
        try {
            return Currency.getInstance(currencyCode).getDefaultFractionDigits();
        } catch (IllegalArgumentException unknownCode) {
            return -1;
        }
    }

    private static String grouped(String integerPart) {
        StringBuilder out = new StringBuilder(integerPart);
        for (int i = integerPart.length() - 3; i > 0; i -= 3) {
            out.insert(i, ',');
        }
        return out.toString();
    }
}
