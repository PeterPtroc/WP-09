package org.thermal.wp09;

import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Local sensor-site overrides, isolated by camera and published atomically to the reader. */
final class CameraRepairs {
    static final class Map {
        final int[] sites;
        final BlindPixels correction;
        Map(int[] sites, BlindPixels factory) {
            this.sites = sites;
            correction = sites.length == 0 ? null : new BlindPixels(sites, factory);
        }
    }

    final BlindPixels factory;
    private final SharedPreferences prefs;
    private final String key;
    volatile Map current;

    CameraRepairs(SharedPreferences prefs, String serial, byte[] kb, BlindPixels factory) {
        this.prefs = prefs;
        this.factory = factory;
        boolean hasSerial = serial != null && !serial.trim().isEmpty();
        key = hasSerial || factory != null ? identity(hasSerial ? serial : "", factory == null ? null : kb) : null;
        Set<String> saved = editable() ? prefs.getStringSet(key, Collections.emptySet()) : Collections.emptySet();
        int[] sites = new int[Math.min(saved.size(), BlindPixels.MAX_USER)];
        int n = 0;
        for (String s : saved) {
            try {
                int p = Integer.parseInt(s);
                if (n < sites.length && valid(p)) sites[n++] = p;
            } catch (NumberFormatException ignored) {}
        }
        sites = Arrays.copyOf(sites, n);
        Arrays.sort(sites);
        current = new Map(sites, factory);
    }

    private static String identity(String serial, byte[] kb) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(serial.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (kb != null) digest.update(kb);
            return "sites." + Base64.encodeToString(digest.digest(), Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    boolean editable() { return prefs != null && key != null; }

    private boolean valid(int p) {
        return p >= 0 && p < ThermalFrame.PIXELS && (factory == null || !factory.contains(p));
    }

    boolean change(int p, boolean clear) {
        if (!editable() || (!clear && !valid(p))) return false;
        int[] previous = current.sites;
        int[] sites = BlindPixels.NONE;
        if (!clear) {
            int i = Arrays.binarySearch(previous, p);
            if (i >= 0) {
                sites = Arrays.copyOf(previous, previous.length - 1);
                System.arraycopy(previous, i + 1, sites, i, sites.length - i);
            } else {
                if (previous.length >= BlindPixels.MAX_USER) return false;
                sites = Arrays.copyOf(previous, previous.length + 1);
                sites[previous.length] = p;
                Arrays.sort(sites);
            }
        }
        Set<String> saved = new HashSet<>();
        for (int site : sites) saved.add(Integer.toString(site));
        prefs.edit().putStringSet(key, saved).apply();
        current = new Map(sites, factory);
        return true;
    }
}
