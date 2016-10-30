package net.filebot.format;

import static java.util.Arrays.*;
import static java.util.Collections.*;
import static java.util.stream.Collectors.*;
import static net.filebot.Logging.*;
import static net.filebot.MediaTypes.*;
import static net.filebot.WebServices.*;
import static net.filebot.format.Define.*;
import static net.filebot.format.ExpressionFormatMethods.*;
import static net.filebot.hash.VerificationUtilities.*;
import static net.filebot.media.MediaDetection.*;
import static net.filebot.media.XattrMetaInfo.*;
import static net.filebot.similarity.Normalization.*;
import static net.filebot.subtitle.SubtitleUtilities.*;
import static net.filebot.util.FileUtilities.*;
import static net.filebot.util.RegularExpressions.*;
import static net.filebot.util.StringUtilities.*;
import static net.filebot.web.EpisodeUtilities.*;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import net.filebot.ApplicationFolder;
import net.filebot.Cache;
import net.filebot.CacheType;
import net.filebot.Language;
import net.filebot.MediaTypes;
import net.filebot.MetaAttributeView;
import net.filebot.Resource;
import net.filebot.Settings;
import net.filebot.hash.HashType;
import net.filebot.media.MetaAttributes;
import net.filebot.media.NamingStandard;
import net.filebot.mediainfo.MediaInfo;
import net.filebot.mediainfo.MediaInfo.StreamKind;
import net.filebot.mediainfo.MediaInfoException;
import net.filebot.similarity.Normalization;
import net.filebot.similarity.SimilarityComparator;
import net.filebot.util.FileUtilities;
import net.filebot.util.WeakValueHashMap;
import net.filebot.web.AudioTrack;
import net.filebot.web.Episode;
import net.filebot.web.EpisodeFormat;
import net.filebot.web.Movie;
import net.filebot.web.MoviePart;
import net.filebot.web.MultiEpisode;
import net.filebot.web.Person;
import net.filebot.web.SeriesInfo;
import net.filebot.web.SimpleDate;
import net.filebot.web.SortOrder;
import net.filebot.web.TMDbClient.MovieInfo;
import net.filebot.web.TheTVDBSeriesInfo;

public class MediaBindingBean {

	private final Object infoObject;
	private final File mediaFile;
	private final Map<File, ?> context;

	private MediaInfo mediaInfo;

	public MediaBindingBean(Object infoObject, File mediaFile) {
		this(infoObject, mediaFile, singletonMap(mediaFile, infoObject));
	}

	public MediaBindingBean(Object infoObject, File mediaFile, Map<File, ?> context) {
		this.infoObject = infoObject;
		this.mediaFile = mediaFile;
		this.context = context;
	}

	@Define("object")
	public Object getInfoObject() {
		return infoObject;
	}

	@Define("file")
	public File getFileObject() {
		return mediaFile;
	}

	@Define(undefined)
	public <T> T undefined(String name) {
		// omit expressions that depend on undefined values
		throw new BindingException(name, EXCEPTION_UNDEFINED);
	}

	@Define("n")
	public String getName() {
		if (infoObject instanceof Episode)
			return getEpisode().getSeriesName();
		else if (infoObject instanceof Movie)
			return getMovie().getName();
		else if (infoObject instanceof AudioTrack && getAlbumArtist() != null)
			return getAlbumArtist();
		else if (infoObject instanceof AudioTrack)
			return getArtist();
		else if (infoObject instanceof File)
			return FileUtilities.getName((File) infoObject);

		return null;
	}

	@Define("y")
	public Integer getYear() {
		if (infoObject instanceof Episode)
			return getEpisode().getSeriesInfo().getStartDate().getYear();
		if (infoObject instanceof Movie)
			return getMovie().getYear();
		if (infoObject instanceof AudioTrack)
			return getMusic().getAlbumReleaseDate().getYear();

		return null;
	}

	@Define("ny")
	public String getNameWithYear() {
		String n = getName().toString();
		String y = " (" + getYear().toString() + ")";

		// account for TV Shows that contain the year in the series name, e.g. Doctor Who (2005)
		return n.endsWith(y) ? n : n + y;
	}

	@Define("s")
	public Integer getSeasonNumber() {
		// look up season numbers via TheTVDB for AniDB episode data
		if (isAnime(getEpisode())) {
			return getSeasonEpisode().getSeason();
		}

		return getEpisode().getSeason();
	}

	@Define("e")
	public Integer getEpisodeNumber() {
		return getEpisode().getEpisode();
	}

	@Define("es")
	public List<Integer> getEpisodeNumbers() {
		return getEpisodes().stream().map(it -> {
			return it.getEpisode() == null ? it.getSpecial() == null ? null : it.getSpecial() : it.getEpisode();
		}).filter(Objects::nonNull).collect(toList());
	}

	@Define("e00")
	public String getE00() {
		if (isRegularEpisode())
			return getEpisodeNumbers().stream().map(i -> String.format("%02d", i)).collect(joining("-"));
		else
			return "Special " + join(getEpisodeNumbers(), "-");
	}

	@Define("sxe")
	public String getSxE() {
		return EpisodeFormat.SeasonEpisode.formatSxE(getSeasonEpisode()); // try to convert absolute numbers to SxE numbers
	}

	@Define("s00e00")
	public String getS00E00() {
		return EpisodeFormat.SeasonEpisode.formatS00E00(getSeasonEpisode()); // try to convert absolute numbers to SxE numbers
	}

	@Define("t")
	public String getTitle() {
		if (infoObject instanceof Episode) {
			// support multi-episode title formatting
			String t = infoObject instanceof MultiEpisode ? EpisodeFormat.SeasonEpisode.formatMultiTitle(getEpisodes()) : getEpisode().getTitle();

			// enforce title length limit by default
			return truncateText(t, NamingStandard.TITLE_MAX_LENGTH);
		}

		if (infoObject instanceof AudioTrack) {
			return getMusic().getTrackTitle() != null ? getMusic().getTrackTitle() : getMusic().getTitle();
		}

		return null;
	}

	@Define("d")
	public SimpleDate getReleaseDate() throws Exception {
		if (infoObject instanceof Episode)
			return getEpisode().getAirdate();
		if (infoObject instanceof Movie)
			return getMovieInfo().getReleased();
		if (infoObject instanceof AudioTrack)
			return getMusic().getAlbumReleaseDate();
		if (infoObject instanceof File)
			return new SimpleDate(getCreationDate(((File) infoObject)));

		return null;
	}

	@Define("airdate")
	public SimpleDate getAirdate() {
		return getEpisode().getAirdate();
	}

	@Define("age")
	public Long getAgeInDays() throws Exception {
		SimpleDate releaseDate = getReleaseDate();

		if (releaseDate != null) {
			long days = ChronoUnit.DAYS.between(releaseDate.toLocalDate(), LocalDateTime.now());
			if (days >= 0) {
				return days;
			}
		}

		return null;
	}

	@Define("startdate")
	public SimpleDate getStartDate() {
		return getEpisode().getSeriesInfo().getStartDate();
	}

	@Define("absolute")
	public Integer getAbsoluteEpisodeNumber() {
		return getEpisode().getAbsolute();
	}

	@Define("special")
	public Integer getSpecialNumber() {
		return getEpisode().getSpecial();
	}

	@Define("series")
	public SeriesInfo getSeriesInfo() {
		return getEpisode().getSeriesInfo();
	}

	@Define("alias")
	public List<String> getAliasNames() {
		if (infoObject instanceof Movie)
			return asList(getMovie().getAliasNames());
		if (infoObject instanceof Episode)
			return getSeriesInfo().getAliasNames();

		return null;
	}

	@Define("primaryTitle")
	public String getPrimaryTitle() throws Exception {
		if (infoObject instanceof Movie)
			return getPrimaryMovieInfo().getOriginalName();
		if (infoObject instanceof Episode)
			return getPrimarySeriesInfo().getName(); // force English series name for TheTVDB data or default to SeriesInfo name (for AniDB episode data this would be the primary title)

		return null;
	}

	@Define("id")
	public Object getId() throws Exception {
		if (infoObject instanceof Episode)
			return getEpisode().getSeriesInfo().getId();
		if (infoObject instanceof Movie)
			return getMovie().getId();
		if (infoObject instanceof AudioTrack)
			return getMusic().getMBID();

		return null;
	}

	@Define("tmdbid")
	public String getTmdbId() throws Exception {
		if (getMovie().getTmdbId() > 0)
			return String.valueOf(getMovie().getTmdbId());
		if (getMovie().getImdbId() > 0)
			return getPrimaryMovieInfo().getId().toString(); // lookup IMDbID for TMDbID

		return null;
	}

	@Define("imdbid")
	public String getImdbId() throws Exception {
		if (getMovie().getImdbId() > 0)
			return String.format("tt%07d", getMovie().getImdbId());
		if (getMovie().getTmdbId() > 0)
			return String.format("tt%07d", getPrimaryMovieInfo().getImdbId()); // lookup IMDbID for TMDbID

		return null;
	}

	@Define("vc")
	public String getVideoCodec() {
		// e.g. XviD, x264, DivX 5, MPEG-4 Visual, AVC, etc.
		String codec = getMediaInfo(StreamKind.Video, 0, "Encoded_Library_Name", "Encoded_Library/Name", "CodecID/Hint", "Format");

		// get first token (e.g. DivX 5 => DivX)
		return SPACE.splitAsStream(codec).findFirst().get();
	}

	@Define("ac")
	public String getAudioCodec() {
		// e.g. AC-3, DTS, AAC, Vorbis, MP3, etc.
		String codec = getMediaInfo(StreamKind.Audio, 0, "CodecID/Hint", "Format");

		// remove punctuation (e.g. AC-3 => AC3)
		return PUNCTUATION_OR_SPACE.matcher(codec).replaceAll("");
	}

	@Define("cf")
	public String getContainerFormat() {
		// container format extensions (e.g. avi, mkv mka mks, OGG, etc.)
		String extensions = getMediaInfo(StreamKind.General, 0, "Codec/Extensions", "Format");

		// get first extension
		return SPACE.splitAsStream(extensions).findFirst().get().toLowerCase();
	}

	@Define("vf")
	public String getVideoFormat() {
		int width = Integer.parseInt(getMediaInfo(StreamKind.Video, 0, "Width"));
		int height = Integer.parseInt(getMediaInfo(StreamKind.Video, 0, "Height"));

		int ns = 0;
		int[] ws = new int[] { 15360, 7680, 3840, 1920, 1280, 1024, 854, 852, 720, 688, 512, 320 };
		int[] hs = new int[] { 8640, 4320, 2160, 1080, 720, 576, 576, 480, 480, 360, 240, 240 };
		for (int i = 0; i < ws.length - 1; i++) {
			if ((width >= ws[i] || height >= hs[i]) || (width > ws[i + 1] && height > hs[i + 1])) {
				ns = hs[i];
				break;
			}
		}
		if (ns > 0) {
			// e.g. 720p, nobody actually wants files to be tagged as interlaced, e.g. 720i
			return String.format("%dp", ns);
		}
		return null; // video too small
	}

	@Define("hpi")
	public String getExactVideoFormat() {
		String height = getMediaInfo(StreamKind.Video, 0, "Height");
		String scanType = getMediaInfo(StreamKind.Video, 0, "ScanType");

		// e.g. 720p
		return height + Character.toLowerCase(scanType.charAt(0));
	}

	@Define("af")
	public String getAudioChannels() {
		String channels = getMediaInfo(StreamKind.Audio, 0, "Channel(s)_Original", "Channel(s)");

		// get first number, e.g. 6ch
		return SPACE.splitAsStream(channels).filter(DIGIT.asPredicate()).findFirst().get() + "ch";
	}

	@Define("channels")
	public String getAudioChannelPositions() {
		String channels = getMediaInfo(StreamKind.Audio, 0, "ChannelPositions/String2", "Channel(s)_Original", "Channel(s)");

		// e.g. ChannelPositions/String2: 3/2/2.1 / 3/2/0.1 (one audio stream may contain multiple multi-channel streams)
		double d = SPACE.splitAsStream(channels).mapToDouble(s -> {
			try {
				return SLASH.splitAsStream(s).mapToDouble(Double::parseDouble).reduce(0, (a, b) -> a + b);
			} catch (NumberFormatException e) {
				return 0;
			}
		}).filter(it -> it > 0).max().getAsDouble();

		return BigDecimal.valueOf(d).setScale(1, RoundingMode.HALF_UP).toPlainString();
	}

	@Define("resolution")
	public String getVideoResolution() {
		return join(getDimension(), "x"); // e.g. 1280x720
	}

	@Define("bitdepth")
	public int getVideoBitDepth() {
		String bitdepth = getMediaInfo(StreamKind.Video, 0, "BitDepth");

		return Integer.parseInt(bitdepth);
	}

	@Define("ws")
	public String getWidescreen() {
		List<Integer> dim = getDimension();

		// width-to-height aspect ratio greater than 1.37:1
		return (float) dim.get(0) / dim.get(1) > 1.37f ? "WS" : null;
	}

	@Define("sdhd")
	public String getVideoDefinitionCategory() {
		List<Integer> dim = getDimension();

		// SD (less than 720 lines) or HD (more than 720 lines)
		return dim.get(0) >= 1280 || dim.get(1) >= 720 ? "HD" : "SD";
	}

	@Define("dim")
	public List<Integer> getDimension() {
		String width = getMediaInfo(StreamKind.Video, 0, "Width");
		String height = getMediaInfo(StreamKind.Video, 0, "Height");

		return asList(Integer.parseInt(width), Integer.parseInt(height));
	}

	@Define("original")
	public String getOriginalFileName() {
		String name = xattr.getOriginalName(getMediaFile());

		return name != null ? getNameWithoutExtension(name) : null;
	}

	@Define("xattr")
	public Object getMetaAttributesObject() throws Exception {
		return xattr.getMetaInfo(getMediaFile());
	}

	@Define("crc32")
	public String getCRC32() throws Exception {
		// use inferred media file
		File inferredMediaFile = getInferredMediaFile();

		// try to get checksum from file name
		Optional<String> embeddedChecksum = stream(getFileNames(inferredMediaFile)).map(Normalization::getEmbeddedChecksum).filter(Objects::nonNull).findFirst();
		if (embeddedChecksum.isPresent()) {
			return embeddedChecksum.get();
		}

		// try to get checksum from sfv file
		String checksum = getHashFromVerificationFile(inferredMediaFile, HashType.SFV, 3);
		if (checksum != null) {
			return checksum;
		}

		// try CRC32 xattr (as stored by verify script)
		try {
			MetaAttributeView xattr = new MetaAttributeView(inferredMediaFile);
			checksum = xattr.get("CRC32");
			if (checksum != null) {
				return checksum;
			}
		} catch (Exception e) {
			// ignore if xattr metadata is not supported for the given file
		}

		// calculate checksum from file
		Cache cache = Cache.getCache("crc32", CacheType.Ephemeral);
		return (String) cache.computeIfAbsent(inferredMediaFile.getCanonicalPath(), it -> crc32(inferredMediaFile));
	}

	@Define("fn")
	public String getFileName() {
		// name without file extension
		return FileUtilities.getName(getMediaFile());
	}

	@Define("ext")
	public String getExtension() {
		// file extension
		return FileUtilities.getExtension(getMediaFile());
	}

	@Define("source")
	public String getVideoSource() {
		// look for video source patterns in media file and it's parent folder (use inferred media file)
		return releaseInfo.getVideoSource(getFileNames(getInferredMediaFile()));
	}

	@Define("tags")
	public List<String> getVideoTags() {
		// look for video source patterns in media file and it's parent folder (use inferred media file)
		List<String> matches = releaseInfo.getVideoTags(getFileNames(getInferredMediaFile()));
		if (matches.isEmpty()) {
			return null;
		}

		// heavy normalization for whatever text was captured with the tags pattern
		return matches.stream().map(s -> {
			return lowerTrail(upperInitial(normalizePunctuation(normalizeSpace(s, " "))));
		}).sorted().distinct().collect(toList());
	}

	@Define("s3d")
	public String getStereoscopic3D() {
		return releaseInfo.getStereoscopic3D(getFileNames(getInferredMediaFile()));
	}

	@Define("group")
	public String getReleaseGroup() throws Exception {
		// reduce false positives by removing the know titles from the name
		Pattern[] nonGroupPattern = { releaseInfo.getCustomRemovePattern(getKeywords()), releaseInfo.getVideoSourcePattern(), releaseInfo.getVideoFormatPattern(true), releaseInfo.getResolutionPattern(), releaseInfo.getStructureRootPattern() };

		// consider foldername, filename and original filename of inferred media file
		String[] filenames = stream(getFileNames(getInferredMediaFile())).map(s -> releaseInfo.clean(s, nonGroupPattern)).toArray(String[]::new);

		// look for release group names in media file and it's parent folder
		return releaseInfo.getReleaseGroup(filenames);
	}

	@Define("subt")
	public String getSubtitleTags() throws Exception {
		if (!SUBTITLE_FILES.accept(getMediaFile())) {
			return null;
		}

		Language language = getLanguageTag();
		if (language != null) {
			String tag = '.' + language.getISO3B(); // Plex only supports ISO 639-2/B language codes
			String category = releaseInfo.getSubtitleCategoryTag(getFileNames(getMediaFile()));
			if (category != null) {
				return tag + '.' + category;
			}
			return tag;
		}

		return null;
	}

	@Define("lang")
	public Language getLanguageTag() throws Exception {
		// grep language from filename
		Locale languageTag = releaseInfo.getSubtitleLanguageTag(getFileNames(getMediaFile()));
		if (languageTag != null) {
			return Language.getLanguage(languageTag);
		}

		// detect language from subtitle text content
		if (SUBTITLE_FILES.accept(getMediaFile())) {
			try {
				return detectSubtitleLanguage(getMediaFile());
			} catch (Exception e) {
				throw new RuntimeException("Failed to detect subtitle language: " + e, e);
			}
		}

		return null;
	}

	@Define("languages")
	public List<Language> getSpokenLanguages() throws Exception {
		if (infoObject instanceof Movie) {
			List<Locale> languages = getMovieInfo().getSpokenLanguages();
			return languages.stream().map(Language::getLanguage).filter(Objects::nonNull).collect(toList());
		}
		if (infoObject instanceof Episode) {
			String language = getSeriesInfo().getLanguage();
			return Stream.of(language).map(Language::findLanguage).filter(Objects::nonNull).collect(toList());
		}
		return null;
	}

	@Define("runtime")
	public Integer getRuntime() throws Exception {
		if (infoObject instanceof Movie)
			return getMovieInfo().getRuntime();
		if (infoObject instanceof Episode)
			return getSeriesInfo().getRuntime();

		return null;
	}

	@Define("actors")
	public List<String> getActors() throws Exception {
		if (infoObject instanceof Movie)
			return getMovieInfo().getActors();
		if (infoObject instanceof Episode)
			return TheTVDBv2.getActors(getSeriesInfo().getId(), Locale.ENGLISH).stream().map(Person::getName).collect(toList()); // use TheTVDB API v2 to retrieve actors info

		return null;
	}

	@Define("genres")
	public List<String> getGenres() throws Exception {
		if (infoObject instanceof Movie)
			return getMovieInfo().getGenres();
		if (infoObject instanceof Episode)
			return getSeriesInfo().getGenres();

		return null;
	}

	@Define("genre")
	public String getPrimaryGenre() throws Exception {
		return getGenres().iterator().next();
	}

	@Define("director")
	public Object getDirector() throws Exception {
		if (infoObject instanceof Movie)
			return getMovieInfo().getDirector();

		return null;
	}

	@Define("certification")
	public String getCertification() throws Exception {
		if (infoObject instanceof Movie)
			return getMovieInfo().getCertification();
		if (infoObject instanceof Episode)
			return getSeriesInfo().getCertification();

		return null;
	}

	@Define("rating")
	public Double getRating() throws Exception {
		if (infoObject instanceof Movie)
			return getMovieInfo().getRating();
		if (infoObject instanceof Episode)
			return getSeriesInfo().getRating();

		return null;
	}

	@Define("collection")
	public String getCollection() throws Exception {
		if (infoObject instanceof Movie)
			return getMovieInfo().getCollection();

		return null;
	}

	@Define("info")
	public synchronized AssociativeScriptObject getMetaInfo() throws Exception {
		if (infoObject instanceof Movie)
			return createPropertyBindings(getMovieInfo());
		if (infoObject instanceof Episode)
			return createPropertyBindings(getSeriesInfo());

		return null;
	}

	@Define("omdb")
	public synchronized AssociativeScriptObject getOmdbApiInfo() throws Exception {
		if (infoObject instanceof Movie) {
			if (getMovie().getImdbId() > 0) {
				return createPropertyBindings(OMDb.getMovieInfo(getMovie()));
			}
			if (getMovie().getTmdbId() > 0) {
				Integer imdbId = getPrimaryMovieInfo().getImdbId();
				return createPropertyBindings(OMDb.getMovieInfo(new Movie(imdbId)));
			}
		}

		if (infoObject instanceof Episode) {
			TheTVDBSeriesInfo info = (TheTVDBSeriesInfo) getPrimarySeriesInfo();
			int imdbId = matchInteger(info.getImdbId());
			return createPropertyBindings(OMDb.getMovieInfo(new Movie(imdbId)));
		}

		return null;
	}

	@Define("localize")
	public Object getLocalizedInfoObject() throws Exception {
		return new DynamicBindings(Language::availableLanguages, k -> {
			Language language = Language.findLanguage(k);
			if (language == null) {
				return undefined(k);
			}

			try {
				if (infoObject instanceof Movie) {
					MovieInfo m = getMovieInfo(language.getLocale(), true);
					return createPropertyBindings(m);
				}
				if (infoObject instanceof Episode) {
					SeriesInfo i = getSeriesInfo();
					List<Episode> es = getEpisodeListProvider(i.getDatabase()).getEpisodeList(i.getId(), SortOrder.forName(i.getOrder()), language.getLocale());
					Episode e = es.stream().filter(it -> getEpisode().getNumbers().equals(it.getNumbers())).findFirst().get();
					return createPropertyBindings(e);
				}
			} catch (Exception e) {
				throw new BindingException(k, e);
			}

			return undefined(k);
		});
	}

	@Define("az")
	public String getSortInitial() {
		try {
			return sortInitial(getCollection().toString());
		} catch (Exception e) {
			return sortInitial(getName());
		}
	}

	@Define("anime")
	public boolean isAnimeEpisode() {
		return getEpisodes().stream().anyMatch(it -> isAnime(it));
	}

	@Define("regular")
	public boolean isRegularEpisode() {
		return getEpisodes().stream().anyMatch(it -> isRegular(it));
	}

	@Define("abs2sxe")
	public Episode getSeasonEpisode() {
		if (getEpisodes().stream().allMatch(it -> isAnime(it) && isRegular(it) && !isAbsolute(it))) {
			try {
				return getEpisodeByAbsoluteNumber(getEpisode(), TheTVDB, SortOrder.Airdate);
			} catch (Exception e) {
				debug.warning(e::toString);
			}
		}
		return getEpisode();
	}

	@Define("episodelist")
	public List<Episode> getEpisodeList() throws Exception {
		SeriesInfo i = getSeriesInfo();

		return getEpisodeListProvider(i.getDatabase()).getEpisodeList(i.getId(), SortOrder.forName(i.getOrder()), new Locale(i.getLanguage()));
	}

	@Define("sy")
	public List<Integer> getSeasonYears() throws Exception {
		return getEpisodeList().stream().filter(e -> isRegular(e) && e.getSeason().equals(getSeasonNumber()) && e.getAirdate() != null).map(e -> e.getAirdate().getYear()).sorted().distinct().collect(toList());
	}

	@Define("sc")
	public Integer getSeasonCount() throws Exception {
		return getEpisodeList().stream().filter(e -> isRegular(e) && e.getSeason() != null).map(Episode::getSeason).max(Integer::compare).get();
	}

	@Define("mediaTitle")
	public String getMediaTitle() {
		return getMediaInfo(StreamKind.General, 0, "Title", "Movie");
	}

	@Define("textLanguages")
	public List<Language> getTextLanguageList() {
		return getMediaInfo(StreamKind.Text, "Language").filter(Objects::nonNull).distinct().map(Language::findLanguage).filter(Objects::nonNull).collect(toList());
	}

	@Define("bitrate")
	public Long getOverallBitRate() {
		return new Double(getMediaInfo(StreamKind.General, 0, "OverallBitRate")).longValue();
	}

	@Define("duration")
	public Long getDuration() {
		return new Double(getMediaInfo(StreamKind.General, 0, "Duration")).longValue();
	}

	@Define("seconds")
	public Integer getSeconds() {
		return (int) (getDuration() / 1000);
	}

	@Define("minutes")
	public Integer getDurationInMinutes() {
		return (int) (getDuration() / 60000);
	}

	@Define("media")
	public AssociativeScriptObject getGeneralMediaInfo() {
		return createMediaInfoBindings(StreamKind.General).get(0);
	}

	@Define("menu")
	public AssociativeScriptObject getMenuInfo() {
		return createMediaInfoBindings(StreamKind.Menu).get(0);
	}

	@Define("image")
	public AssociativeScriptObject getImageInfo() {
		return createMediaInfoBindings(StreamKind.Image).get(0);
	}

	@Define("video")
	public List<AssociativeScriptObject> getVideoInfoList() {
		return createMediaInfoBindings(StreamKind.Video);
	}

	@Define("audio")
	public List<AssociativeScriptObject> getAudioInfoList() {
		return createMediaInfoBindings(StreamKind.Audio);
	}

	@Define("text")
	public List<AssociativeScriptObject> getTextInfoList() {
		return createMediaInfoBindings(StreamKind.Text);
	}

	@Define("chapters")
	public List<AssociativeScriptObject> getChaptersInfoList() {
		return createMediaInfoBindings(StreamKind.Chapters);
	}

	@Define("artist")
	public String getArtist() {
		return getMusic().getArtist();
	}

	@Define("albumArtist")
	public String getAlbumArtist() {
		return getMusic().getAlbumArtist();
	}

	@Define("album")
	public String getAlbum() {
		return getMusic().getAlbum();
	}

	@Define("episode")
	public Episode getEpisode() {
		return (Episode) infoObject;
	}

	@Define("episodes")
	public List<Episode> getEpisodes() {
		return getMultiEpisodeList(getEpisode());
	}

	@Define("movie")
	public Movie getMovie() {
		return (Movie) infoObject;
	}

	@Define("music")
	public AudioTrack getMusic() {
		return (AudioTrack) infoObject;
	}

	@Define("pi")
	public Integer getPart() {
		if (infoObject instanceof AudioTrack)
			return getMusic().getTrack();
		if (infoObject instanceof MoviePart)
			return ((MoviePart) infoObject).getPartIndex();

		return null;
	}

	@Define("pn")
	public Integer getPartCount() {
		if (infoObject instanceof AudioTrack)
			return getMusic().getTrackCount();
		if (infoObject instanceof MoviePart)
			return ((MoviePart) infoObject).getPartCount();

		return null;
	}

	@Define("type")
	public String getInfoObjectType() {
		return infoObject.getClass().getSimpleName();
	}

	@Define("mime")
	public List<String> getMediaType() throws Exception {
		// format engine does not allow / in binding value
		return SLASH.splitAsStream(MediaTypes.getDefault().getMediaType(getExtension())).collect(toList());
	}

	@Define("mediaPath")
	public File getMediaPath() throws Exception {
		return getStructurePathTail(getMediaFile());
	}

	@Define("f")
	public File getMediaFile() {
		// make sure file is not null, and that it is an existing file
		if (mediaFile == null) {
			throw new IllegalStateException(EXCEPTION_SAMPLE_FILE_NOT_SET);
		}
		return mediaFile;
	}

	@Define("folder")
	public File getMediaParentFolder() {
		return getMediaFile().getParentFile();
	}

	@Define("bytes")
	public Long getFileSize() {
		return getInferredMediaFile().length();
	}

	@Define("megabytes")
	public String getFileSizeInMegaBytes() {
		return String.format("%.0f", getFileSize() / Math.pow(1000, 2));
	}

	@Define("gigabytes")
	public String getFileSizeInGigaBytes() {
		return String.format("%.1f", getFileSize() / Math.pow(1000, 3));
	}

	@Define("encodedDate")
	public SimpleDate getEncodedDate() {
		String date = getMediaInfo(StreamKind.General, 0, "Encoded_Date"); // e.g. UTC 2008-01-08 19:54:39
		ZonedDateTime time = ZonedDateTime.parse(date, DateTimeFormatter.ofPattern("zzz uuuu-MM-dd HH:mm:ss"));
		return new SimpleDate(time);
	}

	@Define("today")
	public SimpleDate getToday() {
		return new SimpleDate(LocalDateTime.now());
	}

	@Define("home")
	public File getUserHome() {
		return ApplicationFolder.UserHome.getCanonicalFile();
	}

	@Define("output")
	public File getUserDefinedOutputFolder() throws IOException {
		return new File(Settings.getApplicationArguments().output).getCanonicalFile();
	}

	@Define("defines")
	public Map<String, String> getUserDefinedArguments() throws IOException {
		return unmodifiableMap(Settings.getApplicationArguments().defines);
	}

	@Define("label")
	public String getUserDefinedLabel() throws IOException {
		return getUserDefinedArguments().entrySet().stream().filter(it -> {
			return it.getKey().endsWith("label") && it.getValue() != null && it.getValue().length() > 0;
		}).map(it -> it.getValue()).findFirst().orElse(null);
	}

	@Define("i")
	public Integer getModelIndex() {
		return 1 + identityIndexOf(context.values(), getInfoObject());
	}

	@Define("di")
	public Integer getDuplicateIndex() {
		return 1 + identityIndexOf(context.values().stream().filter(it -> getInfoObject().equals(it)).collect(toList()), getInfoObject());
	}

	@Define("plex")
	public File getPlexStandardPath() throws Exception {
		String path = NamingStandard.Plex.getPath(infoObject);
		try {
			path = path.concat(getSubtitleTags()); // NPE if {subt} is undefined
		} catch (Exception e) {
			// ignore => no language tags
		}
		return new File(path);
	}

	@Define("self")
	public AssociativeScriptObject getSelf() {
		return createBindingObject(mediaFile, infoObject, context);
	}

	@Define("model")
	public List<AssociativeScriptObject> getModel() {
		List<AssociativeScriptObject> result = new ArrayList<AssociativeScriptObject>();
		for (Entry<File, ?> it : context.entrySet()) {
			result.add(createBindingObject(it.getKey(), it.getValue(), context));
		}
		return result;
	}

	@Define("json")
	public String getInfoObjectDump() {
		return MetaAttributes.toJson(infoObject);
	}

	public File getInferredMediaFile() {
		if (getMediaFile().isDirectory()) {
			// just select the first video file in the folder as media sample
			List<File> videos = listFiles(getMediaFile(), VIDEO_FILES, CASE_INSENSITIVE_PATH_ORDER);
			if (videos.size() > 0) {
				return videos.get(0);
			}
		} else if (SUBTITLE_FILES.accept(getMediaFile()) || ((infoObject instanceof Episode || infoObject instanceof Movie) && !VIDEO_FILES.accept(getMediaFile()))) {
			// prefer equal match from current context if possible
			if (context != null) {
				for (Entry<File, ?> it : context.entrySet()) {
					if (infoObject.equals(it.getValue()) && VIDEO_FILES.accept(it.getKey())) {
						return it.getKey();
					}
				}
			}

			// file is a subtitle, or nfo, etc
			String baseName = stripReleaseInfo(FileUtilities.getName(getMediaFile())).toLowerCase();
			List<File> videos = getChildren(getMediaFile().getParentFile(), VIDEO_FILES);

			// find corresponding movie file
			for (File movieFile : videos) {
				if (!baseName.isEmpty() && stripReleaseInfo(FileUtilities.getName(movieFile)).toLowerCase().startsWith(baseName)) {
					return movieFile;
				}
			}

			// still no good match found -> just take the most probable video from the same folder
			if (videos.size() > 0) {
				sort(videos, SimilarityComparator.compareTo(FileUtilities.getName(getMediaFile()), FileUtilities::getName));
				return videos.get(0);
			}
		}

		return getMediaFile();
	}

	public SeriesInfo getPrimarySeriesInfo() throws Exception {
		if (TheTVDB.getIdentifier().equals(getSeriesInfo().getDatabase()))
			return TheTVDB.getSeriesInfo(getSeriesInfo().getId(), Locale.ENGLISH);

		return getSeriesInfo();
	}

	private final Resource<MovieInfo> primaryMovieInfo = Resource.lazy(() -> TheMovieDB.getMovieInfo(getMovie(), Locale.ENGLISH, false));
	private final Resource<MovieInfo> extendedMovieInfo = Resource.lazy(() -> getMovieInfo(getMovie().getLanguage(), true));

	public MovieInfo getPrimaryMovieInfo() throws Exception {
		return primaryMovieInfo.get();
	}

	public MovieInfo getMovieInfo() throws Exception {
		return extendedMovieInfo.get();
	}

	public synchronized MovieInfo getMovieInfo(Locale locale, boolean extendedInfo) throws Exception {
		Movie m = getMovie();

		if (m.getTmdbId() > 0)
			return TheMovieDB.getMovieInfo(m, locale == null ? Locale.ENGLISH : locale, extendedInfo);
		if (m.getImdbId() > 0)
			return OMDb.getMovieInfo(m);

		return null;
	}

	private static final Map<File, MediaInfo> sharedMediaInfoObjects = synchronizedMap(new WeakValueHashMap<File, MediaInfo>(64));

	private synchronized MediaInfo getMediaInfo() {
		// lazy initialize
		if (mediaInfo == null) {
			// use inferred media file (e.g. actual movie file instead of subtitle file)
			File inferredMediaFile = getInferredMediaFile();

			mediaInfo = sharedMediaInfoObjects.computeIfAbsent(inferredMediaFile, f -> {
				try {
					return new MediaInfo().open(f);
				} catch (IOException e) {
					throw new MediaInfoException(e.getMessage());
				}
			});
		}

		return mediaInfo;
	}

	private Integer identityIndexOf(Iterable<?> c, Object o) {
		Iterator<?> itr = c.iterator();
		for (int i = 0; itr.hasNext(); i++) {
			Object next = itr.next();
			if (o == next) {
				return i;
			}
		}
		return null;
	}

	private String getMediaInfo(StreamKind streamKind, int streamNumber, String... keys) {
		for (String key : keys) {
			String value = getMediaInfo().get(streamKind, streamNumber, key);
			if (value.length() > 0) {
				return value;
			}
		}
		return undefined(String.format("%s[%d][%s]", streamKind, streamNumber, join(keys, ", ")));
	}

	private Stream<String> getMediaInfo(StreamKind streamKind, String... keys) {
		return IntStream.range(0, getMediaInfo().streamCount(streamKind)).mapToObj(streamNumber -> {
			return stream(keys).map(key -> {
				return getMediaInfo().get(streamKind, streamNumber, key);
			}).filter(s -> s.length() > 0).findFirst().orElse(null);
		});
	}

	private AssociativeScriptObject createBindingObject(File file, Object info, Map<File, ?> context) {
		MediaBindingBean mediaBindingBean = new MediaBindingBean(info, file, context) {

			@Override
			@Define(undefined)
			public <T> T undefined(String name) {
				return null; // never throw exceptions for empty or null values
			}
		};
		return new AssociativeScriptObject(new ExpressionBindings(mediaBindingBean));
	}

	private AssociativeScriptObject createPropertyBindings(Object object) {
		return new AssociativeScriptObject(new PropertyBindings(object, null)) {

			@Override
			public Object getProperty(String name) {
				Object value = super.getProperty(name);
				if (value == null) {
					return undefined(name);
				}
				if (value instanceof CharSequence) {
					return replacePathSeparators(value.toString()).trim(); // auto-clean value of path separators
				}
				return value;
			}
		};
	}

	private List<AssociativeScriptObject> createMediaInfoBindings(StreamKind kind) {
		return getMediaInfo().snapshot().get(kind).stream().map(AssociativeScriptObject::new).collect(toList());
	}

	private String[] getFileNames(File file) {
		List<String> names = new ArrayList<String>(3);
		names.add(getNameWithoutExtension(file.getName())); // current file name

		String original = xattr.getOriginalName(file);
		if (original != null) {
			names.add(getNameWithoutExtension(original)); // original file name
		}

		File parent = file.getParentFile();
		if (parent != null && parent.getParent() != null) {
			names.add(parent.getName()); // current folder name
		}

		return names.toArray(new String[0]);
	}

	private List<String> getKeywords() {
		// collect key information
		List<Object> keys = new ArrayList<Object>();
		keys.add(getName());
		keys.add(getYear());
		keys.addAll(getAliasNames());

		if (infoObject instanceof Episode) {
			for (Episode it : getEpisodes()) {
				keys.addAll(it.getSeriesNames());
				keys.add(it.getTitle());
			}
		}

		// word list for exclude pattern
		return keys.stream().filter(Objects::nonNull).map(it -> {
			return normalizePunctuation(normalizeSpace(it.toString(), " "));
		}).filter(s -> s.length() > 0).distinct().collect(toList());
	}

	@Override
	public String toString() {
		return String.format("%s ⇔ %s", infoObject, mediaFile == null ? null : mediaFile.getName());
	}

	public static final String EXCEPTION_UNDEFINED = "undefined";
	public static final String EXCEPTION_SAMPLE_FILE_NOT_SET = "Sample file has not been set. Click \"Change Sample\" to select a sample file.";

}
