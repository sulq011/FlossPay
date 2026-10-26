package com.floss83.pay;

import org.json.JSONException;
import org.json.JSONObject;

/** نموذج البطاقة داخل المحفظة — يشمل عنوان الفاتورة للإكمال التلقائي. */
public class Card {
    public String pan;
    public String holder;
    public String expiry;      // MM/YY
    public String cvv;
    public long balanceMinor; // الوحدات الصغرى
    public boolean verified;
    // عنوان الفاتورة (للإكمال التلقائي في صفحات الدفع)
    public String addrStreet;  // صندوق البريد / السطر الأول
    public String addrCity;
    public String addrPostcode;
    public String addrCountry;

    public Card() {}

    /** توافق مع الإصدارات السابقة (بدون عنوان). */
    public Card(String pan, String holder, String expiry, String cvv, long balanceMinor, boolean verified) {
        this(pan, holder, expiry, cvv, balanceMinor, verified, "", "", "", "");
    }

    public Card(String pan, String holder, String expiry, String cvv, long balanceMinor, boolean verified,
                String addrStreet, String addrCity, String addrPostcode, String addrCountry) {
        this.pan = pan;
        this.holder = holder;
        this.expiry = expiry;
        this.cvv = cvv;
        this.balanceMinor = balanceMinor;
        this.verified = verified;
        this.addrStreet = nz(addrStreet);
        this.addrCity = nz(addrCity);
        this.addrPostcode = nz(addrPostcode);
        this.addrCountry = nz(addrCountry);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("pan", pan);
        o.put("holder", holder);
        o.put("expiry", expiry);
        o.put("cvv", cvv);
        o.put("balance", balanceMinor);
        o.put("verified", verified);
        o.put("addr_street", nz(addrStreet));
        o.put("addr_city", nz(addrCity));
        o.put("addr_postcode", nz(addrPostcode));
        o.put("addr_country", nz(addrCountry));
        return o;
    }

    public static Card fromJson(JSONObject o) throws JSONException {
        return new Card(
                o.optString("pan", ""),
                o.optString("holder", ""),
                o.optString("expiry", ""),
                o.optString("cvv", ""),
                o.optLong("balance", 0),
                o.optBoolean("verified", false),
                o.optString("addr_street", ""),
                o.optString("addr_city", ""),
                o.optString("addr_postcode", ""),
                o.optString("addr_country", ""));
    }
}
