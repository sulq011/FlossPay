package com.floss83.pay;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** تخزين البطاقات والإعدادات محليًا (JSON في SharedPreferences). */
public class CardStore {

    /** بطاقات معروفة لدى مُصدر المعمل (issuer_mock) — تُعد "موثّقة". */
    private static final String[] KNOWN_PANS = {
            "4000001234567899", // بطاقة المعمل (رصيد 4000.00)
            "4111111111111111", // VISA تجريبية
            "5555555555554444", // Mastercard تجريبية
            "378282246310005",  // Amex تجريبية
            // بطاقات سعودية مصنفعة (BIN حقيقية لبنوك سعودي + Luhn مولّد — أرقام معملية)
            "4444947123456715", // VISA ديبيت — مصرف الراجحي (BIN 444494)
            "4651542045869133", // VISA كريديت بلاتينيوم — الأهلي SNB (BIN 465154)
            "4659617319028467", // VISA كريديت سيجنشر — مصرف الرياض (BIN 465961)
            "4638695174290386"  // VISA ديبيت — بنك الرياض الفرنسي BSF (BIN 463869)
    };

    /** إصدار البطاقات المزروعة — زيادته تضيف الجديد للمثبت كافة التثبيتات. */
    private static final int SEED_VERSION = 3;

    private final SharedPreferences prefs;

    public CardStore(Context ctx) {
        prefs = ctx.getSharedPreferences("flosspay", Context.MODE_PRIVATE);
    }

    public List<Card> load() {
        List<Card> cards = new ArrayList<Card>();
        String json = prefs.getString("cards", null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    cards.add(Card.fromJson(arr.getJSONObject(i)));
                }
            } catch (JSONException ignored) {
            }
        }
        if (cards.isEmpty()) {
            cards = seed();
            save(cards);
        } else if (prefs.getInt("seed_version", 0) < SEED_VERSION) {
            List<Card> fresh = seed();
            boolean changed = false;
            for (Card s : fresh) {
                Card existing = byPan(cards, s.pan);
                if (existing == null) {
                    cards.add(s);
                    changed = true;
                } else if (isEmpty(existing.addrStreet) || isEmpty(existing.addrCity)
                        || isEmpty(existing.addrPostcode) || isEmpty(existing.addrCountry)) {
                    if (isEmpty(existing.addrStreet)) existing.addrStreet = s.addrStreet;
                    if (isEmpty(existing.addrCity)) existing.addrCity = s.addrCity;
                    if (isEmpty(existing.addrPostcode)) existing.addrPostcode = s.addrPostcode;
                    if (isEmpty(existing.addrCountry)) existing.addrCountry = s.addrCountry;
                    changed = true;
                }
            }
            if (changed) save(cards);
        }
        prefs.edit().putInt("seed_version", SEED_VERSION).apply();
        for (Card c : cards) {
            c.verified = isKnown(c.pan);
        }
        return cards;
    }

    public void save(List<Card> cards) {
        JSONArray arr = new JSONArray();
        for (Card c : cards) {
            try {
                arr.put(c.toJson());
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString("cards", arr.toString()).apply();
    }

    private List<Card> seed() {
        List<Card> cards = new ArrayList<Card>();
        cards.add(new Card("4000001234567899", "Sad Am", "12/29", "123", 400000, true,
                "P.O. Box 12345", "Riyadh", "11564", "Saudi Arabia"));
        cards.add(new Card("4111111111111111", "VISA Test", "11/27", "123", 150000, true,
                "P.O. Box 12345", "Riyadh", "11564", "Saudi Arabia"));
        cards.add(new Card("5555555555554444", "MC Test", "09/28", "123", 50000, true,
                "P.O. Box 12345", "Riyadh", "11564", "Saudi Arabia"));
        cards.add(new Card("378282246310005", "Amex Test", "05/27", "1234", 25000, true,
                "P.O. Box 12345", "Riyadh", "11564", "Saudi Arabia"));
        cards.add(new Card("4444947123456715", "SAD AM", "12/28", "123", 1500000, true,
                "P.O. Box 12345", "Riyadh", "11564", "Saudi Arabia"));
        cards.add(new Card("4651542045869133", "ABDULLAH ALHARBI", "04/29", "456", 2500000, true,
                "P.O. Box 22334", "Jeddah", "21577", "Saudi Arabia"));
        cards.add(new Card("4659617319028467", "MOHAMMED ALQAHTANI", "08/27", "789", 900000, true,
                "P.O. Box 34567", "Riyadh", "11623", "Saudi Arabia"));
        cards.add(new Card("4638695174290386", "FAHAD ALOTAIBI", "01/29", "321", 750000, true,
                "P.O. Box 44556", "Riyadh", "11534", "Saudi Arabia"));
        return cards;
    }

    public boolean isKnown(String pan) {
        for (String k : KNOWN_PANS) {
            if (k.equals(pan)) return true;
        }
        return prefs.getBoolean("known_" + (pan == null ? "" : pan), false);
    }

    /** بعد عملية ناجحة (00/01) تصبح البطاقة "موثّقة لدى المُصدر". */
    public void markKnown(String pan) {
        prefs.edit().putBoolean("known_" + (pan == null ? "" : pan), true).apply();
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public Card byPan(List<Card> cards, String pan) {
        if (pan == null) return null;
        for (Card c : cards) {
            if (pan.equals(c.pan)) return c;
        }
        return null;
    }

    public void adjustBalance(List<Card> cards, String pan, long deltaMinor) {
        Card c = byPan(cards, pan);
        if (c != null) {
            c.balanceMinor += deltaMinor;
            save(cards);
        }
    }

    public String getSelectedPan() {
        return prefs.getString("selected_pan", null);
    }

    public void setSelectedPan(String pan) {
        prefs.edit().putString("selected_pan", pan).apply();
    }

    public String getHost() {
        return prefs.getString("host", "127.0.0.1:5000");
    }

    public void setHost(String host) {
        prefs.edit().putString("host", host).apply();
    }
}
