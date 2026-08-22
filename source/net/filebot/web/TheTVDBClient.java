package net.filebot.web;

import static java.nio.charset.StandardCharsets.*;
import static java.util.Arrays.*;
import static java.util.Collections.*;
import static java.util.stream.Collectors.*;
import static net.filebot.CachedResource.fetchIfModified;
import static net.filebot.Logging.*;
import static net.filebot.util.JsonUtilities.*;
import static net.filebot.util.StringUtilities.*;
import static net.filebot.web.EpisodeUtilities.*;
import static net.filebot.web.WebRequest.*;

import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import javax.swing.Icon;

import net.filebot.Cache;
import net.filebot.CacheType;
import net.filebot.ResourceManager;

public class TheTVDBClient extends AbstractEpisodeListProvider implements ArtworkProvider {

	private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

	private String apikey;

	public TheTVDBClient(String apikey) {
		this.apikey = apikey;
	}

	@Override
	public String getIdentifier() {
		return "TheTVDB";
	}

	@Override
	public Icon getIcon() {
		return ResourceManager.getIcon("search.thetvdb");
	}

	@Override
	public boolean hasSeasonSupport() {
		return true;
	}

	protected Object postJson(String path, Object object) throws Exception {
		ByteBuffer response = post(getEndpoint(path), json(object, false).getBytes(UTF_8), "application/json", null);
		return readJson(UTF_8.decode(response));
	}

	protected Object requestJson(String path, Locale locale, Duration expirationTime) throws Exception {
		Cache cache = Cache.getCache(locale == null || locale == Locale.ROOT ? getName() : getName() + "_" + locale.getLanguage(), CacheType.Monthly);
		return cache.json(path, this::getEndpoint).fetch(fetchIfModified(() -> getRequestHeader(locale))).expire(expirationTime).get();
	}

	protected URL getEndpoint(String path) throws Exception {
		// Updated to V4 Base URL
		return new URL("https://api4.thetvdb.com/v4/" + path);
	}

	private Map<String, String> getRequestHeader(Locale locale) {
		Map<String, String> header = new LinkedHashMap<String, String>(3);

		getLanguageCode(locale).ifPresent(languageCode -> {
			header.put("Accept-Language", languageCode);
		});
		debug.info(String.format("Used apiKey for token: %s", apikey));
		debug.info(String.format("Token: %s", getAuthorizationToken()));
		header.put("Accept", "application/json");
		header.put("Authorization", "Bearer " + getAuthorizationToken());

		return header;
	}

	private Optional<String> getLanguageCode(Locale locale) {
		if (locale == null || locale.getLanguage().isEmpty()) {
			return Optional.empty();
		}
		try {
			// TheTVDB V4 expects 3-letter ISO3 language codes (e.g., "eng", "deu")
			return Optional.of(locale.getISO3Language());
		} catch (MissingResourceException e) {
			// Fallback to the standard language code just in case the ISO3 mapping is missing
			return Optional.of(locale.getLanguage());
		}
	}

	private String token = null;
	private Instant tokenExpireInstant = null;
	private Duration tokenExpireDuration = Duration.ofHours(23);

	private String getAuthorizationToken() {
		synchronized (tokenExpireDuration) {
			if (token == null || (tokenExpireInstant != null && Instant.now().isAfter(tokenExpireInstant))) {
				try {
					Object json = postJson("login", singletonMap("apikey", apikey));
					// V4 wraps login response in a "data" object
					token = getString(getMap(json, "data"), "token");
					tokenExpireInstant = Instant.now().plus(tokenExpireDuration);
				} catch (Exception e) {
					debug.warning(String.format("Using following apiKey failed: %s", apikey));
					throw new IllegalStateException("Failed to retrieve authorization token: " + e.getMessage(), e);
				}
			}
			return token;
		}
	}

	protected List<SearchResult> search(String path, Map<String, Object> query, Locale locale, Duration expirationTime) throws Exception {
		// Append language parameter to V4 search query dynamically if locale is present
		getLanguageCode(locale).ifPresent(lang -> query.put("language", lang));

		Object json = requestJson(path + (query.isEmpty() ? "" : "?" + encodeParameters(query, true)), locale, expirationTime);

		return streamJsonObjects(json, "data").map(it -> {
			// V4 uses tvdb_id for search results
			Integer id = getInteger(it, "tvdb_id");
			if (id == null) {
				id = getInteger(it, "id");
			}
			if (id == null) return null;

			String translatedName = null;
			if (locale != null) {
				translatedName = (String)getMap(it, "translations").get(locale.getISO3Language());
			}
			String seriesName = translatedName == null ? getString(it, "name") : translatedName;
			String[] aliasNames = stream(getArray(it, "aliases")).map(Object::toString).toArray(String[]::new);

			if (seriesName == null || seriesName.startsWith("**") || seriesName.endsWith("**")) {
				debug.warning(format("Ignore invalid series: %s [%d]", seriesName, id));
				return null;
			}

			return new SearchResult(id, seriesName, aliasNames);
		}).filter(Objects::nonNull).collect(toList());
	}

	@Override
	public List<SearchResult> fetchSearchResult(String query, Locale locale) throws Exception {
		Map<String, Object> params = new LinkedHashMap<>();

		// Step 1: Extract just the file/folder name from the path (handles / and \ automatically)
		Path path = Paths.get(query);
		String fileName = path.getFileName().toString();

		// Step 2: Remove the season/episode pattern and everything after it
		String cleanedQuery = fileName.replaceAll("(?i)\\s*[sS]\\d+[eE]\\d+.*", "").trim();
		debug.info(format("Cleaned <%s> to <%s>", query, cleanedQuery));

		params.put("query", cleanedQuery);
		params.put("type", "series");
		return search("search", params, locale, Cache.ONE_DAY);
	}

	@Override
	public TheTVDBSeriesInfo getSeriesInfo(int id, Locale language) throws Exception {
		return getSeriesInfo(new SearchResult(id), language);
	}

	@Override
	public TheTVDBSeriesInfo getSeriesInfo(SearchResult series, Locale locale) throws Exception {
		// V4 Extended Endpoint covers metadata, genres, external IDs
		Object json = requestJson("series/" + series.getId() + "/extended", locale, Cache.ONE_WEEK);
		Object data = getMap(json, "data");

		TheTVDBSeriesInfo info = new TheTVDBSeriesInfo(this, locale, series.getId());

		info.setAliasNames(Stream.of(series.getAliasNames(), getArray(data, "aliases"))
				.flatMap(it -> stream(it))
				.map(it -> {
					if (it instanceof Map) return getString((Map<?, ?>) it, "name");
					return it.toString();
				})
				.filter(Objects::nonNull)
				.distinct().toArray(String[]::new));

		info.setName(getString(data, "name"));

		Object status = getMap(data, "status");
		if (status != null) {
			info.setStatus(getString(status, "name"));
		}

		// Network is found in the companies array in V4
		streamJsonObjects(data, "companies").filter(c -> {
			Object companyType = getMap(c, "companyType");
			return companyType != null && "Network".equalsIgnoreCase(getString(companyType, "companyTypeName"));
		}).findFirst().ifPresent(c -> info.setNetwork(getString(c, "name")));

		info.setRating(getDecimal(data, "score"));
		info.setRatingCount(0); // V4 omits rating count natively

		info.setRuntime(matchInteger(getString(data, "averageRuntime")));
		info.setGenres(streamJsonObjects(data, "genres").map(g -> getString(g, "name")).collect(toList()));
		info.setStartDate(getStringValue(data, "firstAired", SimpleDate::parse));

		// Remote IDs for IMDB
		streamJsonObjects(data, "remoteIds").filter(r -> "IMDB".equalsIgnoreCase(getString(r, "sourceName")))
				.findFirst().ifPresent(r -> info.setImdbId(getString(r, "id")));

		info.setOverview(getString(data, "overview"));
		info.setAirsTime(getString(data, "airsTime"));

		Object airsDays = getMap(data, "airsDays");
		if (airsDays != null) {
			String[] days = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
			for (String day : days) {
				if (Boolean.TRUE.equals(getMap(data, "airsDays").get(day))) {
					info.setAirsDayOfWeek(day.substring(0, 1).toUpperCase() + day.substring(1));
					break;
				}
			}
		}

		info.setBannerUrl(getStringValue(data, "image", this::resolveImage));
		info.setLastUpdated(getStringValue(data, "lastUpdated", s -> {
			try {
				// Parse V4 datetime string (e.g. "2026-07-04 18:14:38") to epoch seconds
				return java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
						.toEpochSecond(java.time.ZoneOffset.UTC);
			} catch (Exception e) {
				try {
					// Fallback just in case some endpoints still return a raw numeric timestamp
					return Long.parseLong(s);
				} catch (NumberFormatException ex) {
					return null;
				}
			}
		}));

		return info;
	}

	@Override
	protected SeriesData fetchSeriesData(SearchResult series, SortOrder sortOrder, Locale locale) throws Exception {
		SeriesInfo info = getSeriesInfo(series, locale);
		info.setOrder(sortOrder.name());

		if (info.getName() == null && !locale.equals(DEFAULT_LOCALE)) {
			return fetchSeriesData(series, sortOrder, DEFAULT_LOCALE);
		}

		List<Episode> episodes = new ArrayList<Episode>();
		List<Episode> specials = new ArrayList<Episode>();

		// V4 episodes fetch using 0-indexed pagination
		for (int i = 0, n = 0; i <= n; i++) {
			String episodeType = "default";
			if (sortOrder == SortOrder.Absolute) episodeType = "absolute";
			if (sortOrder == SortOrder.DVD) episodeType = "dvd";

			Object json = requestJson("series/" + series.getId() + "/episodes/" + episodeType + "?page=" + i, locale, Cache.ONE_DAY);

			// Extract the data object first
			Object data = getMap(json, "data");

			// Pagination links are typically at the root of the JSON response
			Object links = getMap(json, "links");
			if (links != null) {
				String next = getString(links, "next");
				if (next != null && !next.isEmpty()) {
					n = i + 1;
				}
			}

			// Iterate over the "episodes" array INSIDE the "data" object
			streamJsonObjects(data, "episodes").forEach(it -> {
				Integer id = getInteger(it, "id");
				String episodeName = getString(it, "name");

				if (episodeName == null && !locale.equals(DEFAULT_LOCALE)) {
					try {
						episodeName = getEpisodeList(series, sortOrder, DEFAULT_LOCALE).stream().filter(e -> id.equals(e.getId())).findFirst().map(Episode::getTitle).orElse(null);
					} catch (Exception e) {
						debug.warning(cause("Failed to retrieve default episode title", e));
					}
				}

				Integer absoluteNumber = getInteger(it, "absoluteNumber");
				SimpleDate airdate = getStringValue(it, "aired", SimpleDate::parse);

				Integer episodeNumber = getInteger(it, "number");
				Integer seasonNumber = getInteger(it, "seasonNumber");

				if (sortOrder == SortOrder.AbsoluteAirdate && airdate != null) {
					seasonNumber = null;
					episodeNumber = airdate.getYear() * 1_00_00 + airdate.getMonth() * 1_00 + airdate.getDay();
				}

				if (seasonNumber == null || seasonNumber > 0) {
					episodes.add(new Episode(info.getName(), seasonNumber, episodeNumber, episodeName, absoluteNumber, null, airdate, id, new SeriesInfo(info)));
				} else {
					specials.add(new Episode(info.getName(), null, null, episodeName, absoluteNumber, episodeNumber, airdate, id, new SeriesInfo(info)));
				}
			});
		}

		episodes.sort(episodeComparator());
		episodes.addAll(specials);

		return new SeriesData(info, episodes);
	}

	public SearchResult lookupByID(int id, Locale locale) throws Exception {
		if (id <= 0) {
			throw new IllegalArgumentException("Illegal TheTVDB ID: " + id);
		}

		SeriesInfo info = getSeriesInfo(new SearchResult(id), locale);
		return new SearchResult(id, info.getName(), info.getAliasNames());
	}

	public SearchResult lookupByIMDbID(int imdbid, Locale locale) throws Exception {
		if (imdbid <= 0) {
			throw new IllegalArgumentException("Illegal IMDbID ID: " + imdbid);
		}

		// V4 uses a direct path variable for remote IDs
		String fullImdbId = String.format("tt%07d", imdbid);
		Object json = requestJson("search/remoteid/" + fullImdbId, locale, Cache.ONE_MONTH);

		Optional<SearchResult> match = streamJsonObjects(json, "data").map(it -> {
			// Try unwrapping common entity types: series, movie, episode, etc.
			Object entityObj = getMap(it, "series");
			if (entityObj == null) {
				entityObj = getMap(it, "movie");
			}
			if (entityObj == null) {
				entityObj = getMap(it, "episode");
			}
			// Fallback to checking the item itself if no nested entity is found
			if (entityObj == null) {
				entityObj = it;
			}

			Integer id = getInteger(entityObj, "id");
			if (id == null) {
				return null;
			}

			String name = getString(entityObj, "name");
			if (name == null) {
				name = getString(entityObj, "seriesName");
			}

			String[] aliasNames = stream(getArray(entityObj, "aliases"))
					.map(alias -> alias instanceof Map ? getString((Map<?, ?>) alias, "name") : alias.toString())
					.filter(Objects::nonNull)
					.toArray(String[]::new);

			return new SearchResult(id, name, aliasNames);
		}).filter(Objects::nonNull).findFirst();

		return match.orElse(null);
	}

	@Override
	public URI getEpisodeListLink(SearchResult searchResult) {
		return URI.create("https://www.thetvdb.com/?tab=seasonall&id=" + searchResult.getId());
	}

	@Override
	public List<Artwork> getArtwork(int id, String category, Locale locale) throws Exception {
		Object json = requestJson("series/" + id + "/extended", locale, Cache.ONE_MONTH);
		Object data = getMap(json, "data");

		return streamJsonObjects(data, "artworks").map(it -> {
			String imageType = getString(it, "type");
			String resolution = getString(it, "width") + "x" + getString(it, "height");
			URL url = getStringValue(it, "image", this::resolveImage);
			Double rating = getDecimal(it, "score");

			return new Artwork(Stream.of(category, imageType, resolution), url, locale, rating);
		}).sorted(Artwork.RATING_ORDER).collect(toList());
	}

	protected URL resolveImage(String path) {
		if (path == null || path.isEmpty()) {
			return null;
		}

		try {
			// V4 frequently returns full URLs natively
			if (path.startsWith("http")) return new URL(path);
			return new URL("https://artworks.thetvdb.com/banners/" + path);
		} catch (Exception e) {
			throw new IllegalArgumentException(path, e);
		}
	}

	public List<String> getLanguages() throws Exception {
		Object response = requestJson("languages", Locale.ROOT, Cache.ONE_MONTH);
		return streamJsonObjects(response, "data").map(it -> getString(it, "id")).filter(Objects::nonNull).collect(toList());
	}

	public List<Person> getActors(int seriesId, Locale locale) throws Exception {
		Object response = requestJson("series/" + seriesId + "/extended", locale, Cache.ONE_MONTH);
		Object data = getMap(response, "data");

		return streamJsonObjects(data, "characters").map(it -> {
			String name = getString(it, "personName");
			String character = getString(it, "name");
			Integer order = getInteger(it, "sort");
			URL image = getStringValue(it, "image", this::resolveImage);

			return new Person(name, character, Person.ACTOR, null, order, image);
		}).sorted(Person.CREDIT_ORDER).collect(toList());
	}

	public EpisodeInfo getEpisodeInfo(int id, Locale locale) throws Exception {
		Object response = requestJson("episodes/" + id + "/extended", locale, Cache.ONE_MONTH);
		Object data = getMap(response, "data");

		Integer seriesId = getInteger(data, "seriesId");
		String overview = getString(data, "overview");

		// Fallback logic: check series score first, then movie score, then default to 0.0
		Double rating = 0.0;
		Object seriesObj = getMap(data, "series");
		if (seriesObj != null && getDecimal(seriesObj, "score") != null) {
			rating = getDecimal(seriesObj, "score");
		} else {
			Object movieObj = getMap(data, "movie");
			if (movieObj != null && getDecimal(movieObj, "score") != null) {
				rating = getDecimal(movieObj, "score");
			}
		}

		// Votes are typically omitted in V4 payloads, default to 0
		Integer votes = 0;

		List<Person> people = new ArrayList<Person>();

		// Parse directors, writers, and guest stars from the unified "characters" array using "peopleType"
		streamJsonObjects(data, "characters").forEach(it -> {
			String name = getString(it, "personName");
			if (name == null) {
				name = getString(it, "name");
			}

			String peopleType = getString(it, "peopleType");

			if (name != null && peopleType != null) {
				if ("Director".equalsIgnoreCase(peopleType)) {
					people.add(new Person(name, Person.DIRECTOR));
				} else if ("Writer".equalsIgnoreCase(peopleType)) {
					people.add(new Person(name, Person.WRITER));
				} else if ("Guest Star".equalsIgnoreCase(peopleType)) {
					people.add(new Person(name, Person.GUEST_STAR));
				}
			}
		});

		streamJsonObjects(data, "guestStars").forEach(it -> {
			people.add(new Person(getString(it, "personName"), Person.GUEST_STAR));
		});

		streamJsonObjects(data, "characters").forEach(it -> {
			people.add(new Person(getString(it, "personName"), Person.GUEST_STAR));
		});

		return new EpisodeInfo(this, locale, seriesId, id, people, overview, rating, votes);
	}

}