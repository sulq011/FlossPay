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
            "378282246310005"   // Amex تجريبية
    };

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
        }
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
        cards.add(new Card("4000001234567899", "Sad Am", "12/29", "123", 400000, true));
        cards.add(new Card("4111111111111111", "VISA Test", "11/27", "123", 150000, true));
        cards.add(new Card("5555555555554444", "MC Test", "09/28", "123", 50000, true));
        cards.add(new Card("378282246310005", "Amex Test", "05/27", "1234", 25000, true));
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
