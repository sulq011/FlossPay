package com.floss83.pay;

import org.json.JSONException;
import org.json.JSONObject;

/** نموذج البطاقة داخل المحفظة. */
public class Card {
    public String pan;
    public String holder;
    public String expiry;      // MM/YY
    public String cvv;
    public long balanceMinor; // الوحدات الصغرى (سنتات)
    public boolean verified;

    public Card() {}

    public Card(String pan, String holder, String expiry, String cvv, long balanceMinor, boolean verified) {
        this.pan = pan;
        this.holder = holder;
        this.expiry = expiry;
        this.cvv = cvv;
        this.balanceMinor = balanceMinor;
        this.verified = verified;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("pan", pan);
        o.put("holder", holder);
        o.put("expiry", expiry);
        o.put("cvv", cvv);
        o.put("balance", balanceMinor);
        o.put("verified", verified);
        return o;
    }

    public static Card fromJson(JSONObject o) throws JSONException {
        return new Card(
                o.optString("pan", ""),
                o.optString("holder", ""),
                o.optString("expiry", ""),
                o.optString("cvv", ""),
                o.optLong("balance", 0),
                o.optBoolean("verified", false));
    }
}
