package com.vezet.vezetnav;

import org.osmdroid.util.GeoPoint;

/**
 * Created by Aburaka on 27.03.2017.
 */

public class CustomLocation {
    public String Name;
    public int Type;
    public String Description;
    public String SubDescription;
    public float Lon;
    public float Lat;

    public CustomLocation(String name, int type, String desc, String subDescription, float lon, float lat)
    {
        Name = name;
        Type = type;
        Description = desc;
        SubDescription = subDescription;
        Lon = lon;
        Lat = lat;
    }

    public GeoPoint getGeoPoint()
    {
        return new GeoPoint(Lat,Lon);
    }
}
