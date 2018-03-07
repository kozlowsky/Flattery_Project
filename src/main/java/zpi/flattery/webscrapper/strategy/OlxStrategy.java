package zpi.flattery.webscrapper.strategy;

import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;
import zpi.flattery.model.Offer;
import zpi.flattery.model.OfferType;
import zpi.flattery.model.RoomType;
import zpi.flattery.webscrapper.googleapi.GeocodeResponse;
import zpi.flattery.webscrapper.googleapi.GoogleGeocodeApi;
import zpi.flattery.webscrapper.util.OlxUtil;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class OlxStrategy extends java.util.Observable implements ScrapStrategy {

    private static final Logger LOGGER = Logger.getLogger(OlxStrategy.class.getName());
    private static final String BASE_URL = "https://www.olx.pl/";

    private String urlToScrap;

    private RoomType roomType;
    private OfferType offerType;

    private List<Offer> resultOffers;

    public OlxStrategy() {
    }

    @Override
    public List<Offer> getScrappedOffers() {
        return resultOffers;
    }

    @Override
    public void setScrapParameters(RoomType roomType, OfferType offerType, String query, Integer minPrice, Integer maxPrice, String place, Integer radius) {
//      https://www.olx.pl/nieruchomosci/stancje-pokoje/wroclaw/q-balkon/?search%5Bfilter_float_price%3Afrom%5D=500&search%5Bfilter_float_price%3Ato%5D=700&search%5Bfilter_enum_roomsize%5D%5B0%5D=two&search%5Bdist%5D=75
        this.offerType = offerType;
        this.roomType = roomType;
        urlToScrap = BASE_URL;
        if (offerType != null)
            switch (offerType) {
                case Flat:
                    urlToScrap += "nieruchomosci/mieszkania/";
                    break;
                case Room:
                    urlToScrap += "nieruchomosci/stancje-pokoje/";
                    break;
                case House:
                    urlToScrap += "nieruchomosci/domy/";
                    break;
            }
        if (place != null) {
            place = place.replaceAll("\\s+", "-");
            urlToScrap += "q-" + place;
            if (query != null) {
                query = query.replaceAll("\\s+", "-");
                urlToScrap += "-" + query;
            }
            urlToScrap += "/";
        }
        if (minPrice != null || maxPrice != null || radius != null) {
            boolean isAnyParamAdded = false;
            urlToScrap += "?";
            if (minPrice != null) {
                isAnyParamAdded = true;
                urlToScrap += "search%5Bfilter_float_price%3Afrom%5D=" + minPrice;
            }
            if (maxPrice != null) {
                if (isAnyParamAdded)
                    urlToScrap += "&";
                else
                    isAnyParamAdded = true;
                urlToScrap += "search%5Bfilter_float_price%3Ato%5D=" + maxPrice;
            }
            if (radius != null) {
                if (isAnyParamAdded)
                    urlToScrap += "&";
                else
                    isAnyParamAdded = true;
                urlToScrap += "search%5Bdist%5D=" + radius;
            }
            if (roomType != null && offerType == OfferType.Room) {
                if (isAnyParamAdded)
                    urlToScrap += "&";
                else
                    isAnyParamAdded = true;
                urlToScrap += "search%5Bfilter_enum_roomsize%5D%5B0%5D=";
                switch (roomType) {
                    case One:
                        urlToScrap += "one";
                        break;
                    case Two:
                        urlToScrap += "two";
                        break;
                    case ThreeOrMore:
                        urlToScrap += "three";
                        break;
                }
            }
        }
        System.out.println(urlToScrap);
    }

    @Override
    public ArrayList<Offer> scrap() {
        ArrayList<Offer> scrappedOffers = new ArrayList<>();

        //Scrapping offers
        try {
            Document doc = Jsoup.connect(urlToScrap).get();
            Elements offers = doc.select(".offer");
            offers.forEach(element -> {
                Offer offerToAdd = getOfferFromElement(element);
                if (offerToAdd != null)
                    scrappedOffers.add(offerToAdd);
            });
        } catch (IOException e) {
            System.err.println("For '" + urlToScrap + "': " + e.getMessage());
        }

        //Using google maps api to obtain coordinates from scrapped offer and add them
        addGeocodeToOffers(scrappedOffers);
        return scrappedOffers;
    }

    private Offer getOfferFromElement(Element offerElement) {
        String title;
        String link;
        double price;
        LocalDateTime publishedDate;
        String photoUrl;
        String place;

        //substracting link to Offer
        Elements linkElement = offerElement.select("a[class^=thumb vtop inlblk rel tdnone linkWithHash]");
        link = linkElement.attr("href");
        if (link.equals("")) {
            LOGGER.log(Level.FINER, "Error: Offer contains null url, REJECTED");
            return null;
        }

        //going to the deeper level in linkElement to extract imgUrl, there is a possibility for no photo in offer
        Element imgUrlElement = linkElement.select("img").first();
        if (imgUrlElement != null)
            photoUrl = imgUrlElement.attr("src");
        else
            photoUrl = "";

        //Extracting Title and Price
        //There are only 2 strong tags in offer, first one with title and the second one with price
        Elements strongElements = offerElement.select("strong");
        title = strongElements.first().text();

        //Extracting price from offer, removing last 2 chars coz string contains currency(zł) and removing whitespaces for correct parsing to Integer
        String priceString = strongElements.last().text();
        priceString = priceString.substring(0, priceString.length() - 2);
        priceString = priceString.replaceAll("\\s+", "");
        price = Double.parseDouble(priceString);

        //Extracting date
        Element dateElement = offerElement.select("p[class=color-9 lheight16 marginbott5 x-normal]").first();
        publishedDate = OlxUtil.getDateFromString(dateElement.text());

        //Extracting city
        Elements cityElement = offerElement.select("small[class=breadcrumb x-normal]").select("span");
        place = cityElement.first().text();

        return new Offer(title, link, place, offerType, roomType, price, publishedDate, photoUrl);
    }

    private void addGeocodeToOffers(ArrayList<Offer> scrappedOffers) {
        //Creating Retrofit instance
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(GoogleGeocodeApi.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .build();
        GoogleGeocodeApi service = retrofit.create(GoogleGeocodeApi.class);

        //Creating Observable objects from offers and addidng them to list
        List<Observable<Offer>> observableList = new LinkedList<>();
        for (Offer offer : scrappedOffers) {
            observableList.add(service.getGeocode(offer.getPlace(), GoogleGeocodeApi.API_KEY)
                    .map(response -> combineResponseWithOffer(offer, response)));
        }
        //Observables from list are observed on the same thread so the whole process will wait for all of them, before completion
        Observable.fromIterable(observableList)
                .flatMap(task -> {
                    task.observeOn(Schedulers.computation());
                    return task;
                })
                .toList()
                .subscribe(this::handleRespond, this::handleError);
    }

    private Offer combineResponseWithOffer(Offer offer, GeocodeResponse geocodeResponse) {
        offer.setCoordinates("lat=" + geocodeResponse.results[0].geometry.location.lat + ", lng=" + geocodeResponse.results[0].geometry.location.lng);
        return offer;
    }

    private void handleRespond(List<Offer> offerList) {
        resultOffers = offerList;
        setChanged();
        notifyObservers();
    }

    private void handleError(Throwable e) {
        e.printStackTrace();
    }
}
