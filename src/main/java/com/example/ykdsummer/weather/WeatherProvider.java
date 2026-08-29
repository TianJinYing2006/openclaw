package com.example.ykdsummer.weather;

/** Replaceable provider boundary for querying current weather by city name. */
public interface WeatherProvider {

    /** Query current weather for the given city. Throws on invalid input or service failure. */
    WeatherInfo getCurrentWeather(String city);
}
