package org.gallonyin.weworkhk;

import java.util.Locale;

/** 改写 NMEA-0183 语句中的 WGS-84 坐标（RMC/GGA/GLL），重算校验和；纯 JVM。 */
final class NmeaRewriter {
    private NmeaRewriter() {
    }

    static String rewrite(String sentence, double wgsLat, double wgsLng) {
        try {
            if (sentence == null || sentence.isEmpty()) return sentence;
            int star = sentence.indexOf('*');
            String mainPart = star >= 0 ? sentence.substring(0, star) : sentence;
            String tail = star >= 0 && sentence.length() > star + 3 ? sentence.substring(star + 3) : "";
            String[] fields = mainPart.split(",", -1);
            if (fields.length == 0 || fields[0] == null || fields[0].length() < 5) return sentence;
            String type = fields[0];
            boolean modified = false;

            if (type.endsWith("RMC") && fields.length >= 7) {
                String[] lat = nmeaLatitude(wgsLat);
                String[] lng = nmeaLongitude(wgsLng);
                fields[2] = "A";
                fields[3] = lat[0];
                fields[4] = lat[1];
                fields[5] = lng[0];
                fields[6] = lng[1];
                modified = true;
            } else if (type.endsWith("GGA") && fields.length >= 6) {
                String[] lat = nmeaLatitude(wgsLat);
                String[] lng = nmeaLongitude(wgsLng);
                fields[2] = lat[0];
                fields[3] = lat[1];
                fields[4] = lng[0];
                fields[5] = lng[1];
                if (fields.length > 6) fields[6] = "1";
                modified = true;
            } else if (type.endsWith("GLL") && fields.length >= 5) {
                String[] lat = nmeaLatitude(wgsLat);
                String[] lng = nmeaLongitude(wgsLng);
                fields[1] = lat[0];
                fields[2] = lat[1];
                fields[3] = lng[0];
                fields[4] = lng[1];
                if (fields.length > 6) fields[6] = "A";
                modified = true;
            }

            if (!modified) return sentence;
            String newMain = join(fields, ',');
            return newMain + "*" + checksum(newMain) + tail;
        } catch (Throwable t) {
            return sentence;
        }
    }

    static String[] nmeaLatitude(double lat) {
        double abs = Math.abs(lat);
        int degrees = (int) abs;
        double minutes = (abs - degrees) * 60.0;
        return new String[]{
                String.format(Locale.US, "%02d%08.5f", degrees, minutes),
                lat >= 0 ? "N" : "S"
        };
    }

    static String[] nmeaLongitude(double lng) {
        double abs = Math.abs(lng);
        int degrees = (int) abs;
        double minutes = (abs - degrees) * 60.0;
        return new String[]{
                String.format(Locale.US, "%03d%08.5f", degrees, minutes),
                lng >= 0 ? "E" : "W"
        };
    }

    static String checksum(String sentenceWithDollar) {
        int checksum = 0;
        int start = sentenceWithDollar.startsWith("$") ? 1 : 0;
        int end = sentenceWithDollar.indexOf('*');
        int limit = end >= 0 ? end : sentenceWithDollar.length();
        for (int i = start; i < limit; i++) {
            checksum ^= sentenceWithDollar.charAt(i);
        }
        return String.format(Locale.US, "%02X", checksum);
    }

    private static String join(String[] fields, char separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) builder.append(separator);
            builder.append(fields[i]);
        }
        return builder.toString();
    }
}
