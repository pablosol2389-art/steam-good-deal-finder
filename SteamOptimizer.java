import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SteamOptimizer {

    // --- Terminal Colors ---
    private static final String RESET = "";
    private static final String GREEN = "";
    private static final String YELLOW = "";
    private static final String BOLD = "";
    private static final String CYAN = "";

    private static final String SAVE_FILE = "steam_basket.tsv";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(12);
    private static final int DETAIL_BATCH_SIZE = 8;
    private static final long DETAIL_BATCH_DELAY_MS = 1200;
    private static final long IMPORT_DISPLAY_DELAY_MS = 320;

    // --- Data Models ---
    static class Game {
        int id;
        String appid;
        String name;
        double normal;
        double sale;
        boolean skip;
        boolean picked;
        String reviewScore;
        int reviewPercent;
        int reviewCount;
        boolean fromWishlist;
        boolean purchased;

        public Game(int id, String appid, String name) {
            this.id = id;
            this.appid = appid;
            this.name = name;
            this.skip = false;
            this.picked = false;
            this.reviewScore = "N/A";
            this.reviewPercent = 0;
            this.reviewCount = 0;
            this.fromWishlist = false;
            this.purchased = false;
        }

        public double getDiscountPct() {
            if (normal <= 0) return 0;
            return Math.max(0, Math.round((1 - sale / normal) * 100));
        }

        public double getSavings() {
            return Math.max(0, normal - sale);
        }

        @Override
        public String toString() {
            String status = "[    ]";
            String color = RESET;
            if (purchased) { status = "[PURCH]"; color = YELLOW; }
            else if (picked) { status = "[BUY] "; color = GREEN + BOLD; }
            else if (skip) { status = "[SKIP]"; color = YELLOW; }

            int displayPercent = reviewPercent > 0 ? reviewPercent : 0;
            String reviewLabel = (reviewScore != null && !reviewScore.trim().isEmpty() && !reviewScore.equalsIgnoreCase("unknown") && !reviewScore.equalsIgnoreCase("n/a"))
                    ? reviewScore : "N/A";
            String text = String.format("%s ID:%02d | %-30s | Rev: %3d%% (%-13s) | Price: $%5.2f -> $%5.2f (Save $%5.2f, %3.0f%%)",
                    status, id, truncate(name, 30), displayPercent, reviewLabel, normal, sale, getSavings(), getDiscountPct());
            return color + text + RESET;
        }

        private String truncate(String str, int len) {
            if (str == null) return "";
            if (str.length() <= len) return str;
            return str.substring(0, len - 3) + "...";
        }
    }

    static class WeightedItem {
        Game game;
        int weight; 
        int value;

        public WeightedItem(Game game, int weight, int value) {
            this.game = game;
            this.weight = weight;
            this.value = value;
        }
    }

    // --- Global State ---
    private static List<Game> basket = new ArrayList<>();
    private static int idCounter = 1;
    private static double budget = 50.0;
    private static String objective = "bang"; 
    private static String lastSteamId = "";
    private static String steamApiKey = "";
    private static Set<String> ownedGames = new HashSet<>();
    private static Set<String> ownedSteamAppIdsCache = new HashSet<>();
    private static boolean ownedSteamCacheLoaded = false;
    private static String ownedSteamCacheSteamId = "";
    private static Scanner scanner = new Scanner(System.in);
    private static HttpClient client = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // --- Main Loop ---
    public static void main(String[] args) {
        System.out.println("=========================================");
        System.out.println("   STEAM SALE BASKET PLANNER (TERMINAL)  ");
        System.out.println("=========================================");
        
        loadFromFile();
        loadOwnedGames();
        promptForSteamIdOnStartup();

        while (true) {
            printMainMenu();
            System.out.print("> ");

            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1": importLibrary(false); break;
                case "2": importLibrary(true); break;
                case "3": addManualGame(); break;
                case "4": manageBasket(); break;
                case "5": optimizeAndReview(); break;
                case "6": openSettings(); break;
                case "7": importPopularSteamGamesMenu(); break;
                case "8": clearMenu(); break;
                case "0": 
                    saveToFile(); 
                    System.out.println("Saved. Exiting..."); 
                    return;
                default: System.out.println("Invalid choice.");
            }
        }
    }

    private static void printMainMenu() {
        System.out.println("\n--- MAIN MENU ---");
        System.out.printf("Budget: $%.2f | Objective: %s | Games Loaded: %d\n", budget, getObjectiveName(), basket.size());
        System.out.println("1. Quick Sync (Wishlist & Library - Only add new)");
        System.out.println("2. Deep Sync (Wishlist & Library - Update all prices, SLOW)");
        System.out.println("3. Add Game Manually");
        System.out.println("4. View/Manage Full Basket");
        System.out.println(CYAN + BOLD + "5. OPTIMIZE & INTERACTIVE BUY MENU" + RESET);
        System.out.println("6. Settings (Change SteamID, Budget, Objective)");
        System.out.println("7. Import Popular Steam Games (Players / Reviews / Both)");
        System.out.println("8. Clear Basket / Wishlist Items");
        System.out.println("0. Exit");
    }

    // --- Persistence ---
    private static void saveToFile() {
        // FIXED: Enforcing UTF-8 encoding when writing to prevent mangled symbols
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(SAVE_FILE), StandardCharsets.UTF_8))) {
            out.println(lastSteamId + "|" + budget + "|" + objective + "|" + steamApiKey);
            for (Game g : basket) {
                out.printf("%s\t%s\t%f\t%f\t%b\t%s\t%d\t%d\t%b\t%b\n",
                        g.appid, formatGameName(g.name).replace("\t", " "), g.normal, g.sale, g.skip, g.reviewScore, g.reviewPercent, g.reviewCount, g.purchased, g.fromWishlist);
            }
        } catch (IOException e) {
            System.out.println("Failed to save basket: " + e.getMessage());
        }
    }

    private static void loadOwnedGames() {
        ownedGames.clear();
        Path path = Paths.get("owned_games.txt");
        if (!Files.exists(path)) {
            System.out.println("No 'owned_games.txt' found. Skipping owned check.");
            return;
        }

        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    ownedGames.add(trimmed);
                }
            }
            System.out.println(GREEN + "Successfully loaded " + ownedGames.size() + " owned games from 'owned_games.txt'." + RESET);
        } catch (IOException e) {
            System.out.println("Error reading owned_games.txt: " + e.getMessage());
        }
    }

    private static void loadFromFile() {
        Path path = Paths.get(SAVE_FILE);
        if (!Files.exists(path)) return;

        try {
            // FIXED: Enforcing UTF-8 encoding when reading
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.isEmpty()) return;

            String[] config = lines.get(0).split("\\|");
            if (config.length >= 3) {
                lastSteamId = config[0];
                budget = Double.parseDouble(config[1]);
                objective = config[2];
                if (config.length >= 4) steamApiKey = config[3];
            }
            if (steamApiKey.isEmpty()) {
                String envKey = System.getenv("STEAM_API_KEY");
                if (envKey != null && !envKey.trim().isEmpty()) steamApiKey = envKey.trim();
            }

            for (int i = 1; i < lines.size(); i++) {
                String[] parts = lines.get(i).split("\t");
                if (parts.length >= 8) {
                    Game g = new Game(idCounter++, parts[0], parts[1]);
                    g.normal = Double.parseDouble(parts[2]);
                    g.sale = Double.parseDouble(parts[3]);
                    g.skip = Boolean.parseBoolean(parts[4]);
                    g.reviewScore = parts[5];
                    g.reviewPercent = Integer.parseInt(parts[6]);
                    g.reviewCount = Integer.parseInt(parts[7]);
                    if (parts.length >= 10) {
                        g.purchased = Boolean.parseBoolean(parts[8]);
                        g.fromWishlist = Boolean.parseBoolean(parts[9]);
                    }
                    basket.add(g);
                }
            }
            System.out.println(GREEN + "Successfully loaded " + basket.size() + " games from local save." + RESET);
        } catch (Exception e) {
            System.out.println("Error loading save file. Starting fresh.");
        }
    }

    private static void promptForSteamIdOnStartup() {
        if (!lastSteamId.isEmpty()) return;

        System.out.print("Enter your SteamID64 for wishlist/library sync, or press Enter to skip: ");
        String input = scanner.nextLine().trim();
        if (!input.isEmpty()) {
            lastSteamId = input;
            ownedSteamCacheLoaded = false;
            saveToFile();
        }
    }

    // --- Settings Menu ---
    private static void openSettings() {
        while(true) {
            System.out.println("\n--- SETTINGS ---");
            System.out.println("1. SteamID64 : " + (lastSteamId.isEmpty() ? "Not Set" : lastSteamId));
            System.out.println("2. Budget    : $" + budget);
            System.out.println("3. Objective : " + getObjectiveName());
            System.out.println("4. Steam API Key: " + (steamApiKey.isEmpty() ? "Not Set" : "Saved"));
            System.out.println("0. Return to Main Menu");
            System.out.print("Select item to modify > ");
            
            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1":
                    System.out.print("Enter new SteamID64: ");
                    String newId = scanner.nextLine().trim();
                    if (!newId.isEmpty()) {
                        lastSteamId = newId;
                        ownedSteamCacheLoaded = false;
                        ownedSteamCacheSteamId = "";
                    }
                    break;
                case "2":
                    System.out.print("Enter new Budget: $");
                    String newBudget = scanner.nextLine().trim();
                    try {
                        if (!newBudget.isEmpty()) budget = Double.parseDouble(newBudget);
                    } catch (NumberFormatException e) {
                        System.out.println(YELLOW + "Invalid number format." + RESET);
                    }
                    break;
                case "3":
                    System.out.println("Select Objective:");
                    System.out.println("  1. Bang for Buck (Savings x Review Profile)");
                    System.out.println("  2. Total Money Saved");
                    System.out.println("  3. Total List Price Value");
                    System.out.println("  4. Maximize Number of Games");
                    System.out.print("  > ");
                    String objChoice = scanner.nextLine().trim();
                    if (objChoice.equals("1")) objective = "bang";
                    else if (objChoice.equals("2")) objective = "savings";
                    else if (objChoice.equals("3")) objective = "value";
                    else if (objChoice.equals("4")) objective = "count";
                    break;
                case "4":
                    System.out.print("Enter Steam Web API key, or press Enter to clear it: ");
                    steamApiKey = scanner.nextLine().trim();
                    ownedSteamCacheLoaded = false;
                    ownedSteamCacheSteamId = "";
                    break;
                case "0":
                    saveToFile();
                    return;
                default:
                    System.out.println(YELLOW + "Invalid choice." + RESET);
            }
        }
    }

    // --- Features ---
    private static void importLibrary(boolean forceUpdateAll) {
        if (lastSteamId.isEmpty()) {
            System.out.println(YELLOW + "No SteamID detected. Let's set it up." + RESET);
            System.out.print("Enter your numeric SteamID64: ");
            lastSteamId = scanner.nextLine().trim();
            if (lastSteamId.isEmpty()) {
                System.out.println("Canceled.");
                return;
            }
            saveToFile();
        }

        System.out.println("Syncing Steam wishlist and library for ID: " + lastSteamId + " ...");
        try {
            ensureSteamApiKeyForLibrary();
            Set<String> libraryAppIds = new LinkedHashSet<>(getOwnedSteamAppIds());
            libraryAppIds.addAll(ownedGames);

            HttpRequest wlReq = buildSteamRequest("https://api.steampowered.com/IWishlistService/GetWishlist/v1?steamid=" + lastSteamId);
            HttpResponse<String> wlRes = client.send(wlReq, HttpResponse.BodyHandlers.ofString());
            
            Matcher mId = Pattern.compile("\"appid\":\\s*(\\d+)").matcher(wlRes.body());
            Set<String> wishlistAppIds = new LinkedHashSet<>();
            while (mId.find()) wishlistAppIds.add(mId.group(1));

            if (wishlistAppIds.isEmpty() && libraryAppIds.isEmpty()) {
                System.out.println(YELLOW + "No wishlist or library games found. Make sure your profile is public." + RESET);
                return;
            }

            int autoDeleted = 0;
            Iterator<Game> it = basket.iterator();
            while (it.hasNext()) {
                Game g = it.next();
                if (isAppIdOwned(g.appid, libraryAppIds) && !g.purchased) {
                    it.remove();
                    autoDeleted++;
                }
            }
            if (autoDeleted > 0) {
                System.out.println(YELLOW + "Auto-removed " + autoDeleted + " now-owned game(s) from the basket." + RESET);
                saveToFile();
            }

            Set<String> existingIds = new HashSet<>();
            for (Game g : basket) existingIds.add(g.appid);

            Set<String> idsToFetch = new LinkedHashSet<>();
            int stubbedOwnedOnly = 0;
            for (String appid : wishlistAppIds) {
                if (forceUpdateAll || !existingIds.contains(appid)) {
                    idsToFetch.add(appid);
                }
            }
            for (String appid : libraryAppIds) {
                if (forceUpdateAll || !existingIds.contains(appid)) {
                    if (!forceUpdateAll && !wishlistAppIds.contains(appid)) {
                        Game ownedStub = new Game(idCounter++, appid, "Owned Steam App " + appid);
                        ownedStub.purchased = true;
                        ownedStub.fromWishlist = false;
                        basket.add(ownedStub);
                        existingIds.add(appid);
                        stubbedOwnedOnly++;
                        saveToFile();
                    } else {
                        idsToFetch.add(appid);
                    }
                }
            }

            if (idsToFetch.isEmpty()) {
                refreshActiveBuyList();
                if (stubbedOwnedOnly > 0) {
                    System.out.println(GREEN + "Added " + stubbedOwnedOnly + " owned library game(s) as purchased entries." + RESET);
                } else {
                    System.out.println(GREEN + "Local library is already up to date! No new games found." + RESET);
                }
                return;
            }

            System.out.printf("Fetching pricing and review data for %d games...\n", idsToFetch.size());
            if (stubbedOwnedOnly > 0) {
                System.out.println("Added " + stubbedOwnedOnly + " owned library game(s) as purchased entries without slow detail lookups.");
            }
            int addedOrUpdated = 0;
            int totalToFetch = idsToFetch.size();
            int current = 0;
            List<String> fetchList = new ArrayList<>(idsToFetch);
            List<List<String>> batches = new ArrayList<>();
            for (int batchStart = 0; batchStart < fetchList.size(); batchStart += DETAIL_BATCH_SIZE) {
                batches.add(new ArrayList<>(fetchList.subList(batchStart, Math.min(batchStart + DETAIL_BATCH_SIZE, fetchList.size()))));
            }

            CompletableFuture<Map<String, Game>> nextBatchFuture = fetchGameDetailsAndReviewsBatchAsync(batches.get(0), wishlistAppIds, libraryAppIds);
            for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
                List<String> batch = batches.get(batchIndex);
                Map<String, Game> fetchedGames = nextBatchFuture.join();
                if (batchIndex + 1 < batches.size()) {
                    nextBatchFuture = fetchGameDetailsAndReviewsBatchAsync(batches.get(batchIndex + 1), wishlistAppIds, libraryAppIds);
                }

                for (String appid : batch) {
                    current++;

                    Game fetched = fetchedGames.get(appid);
                    if (fetched == null) {
                        fetched = new Game(idCounter++, appid, "Unknown Steam App " + appid);
                    }

                    boolean fromWishlist = wishlistAppIds.contains(appid);
                    boolean purchased = libraryAppIds.contains(appid);
                    Game existing = findGameByAppId(appid);
                    if (existing == null) {
                        fetched.name = formatGameName(fetched.name);
                        fetched.fromWishlist = fromWishlist;
                        fetched.purchased = purchased;
                        basket.add(fetched);
                    } else {
                        existing.name = formatGameName(fetched.name);
                        existing.normal = fetched.normal;
                        existing.sale = fetched.sale;
                        existing.reviewScore = fetched.reviewScore;
                        existing.reviewPercent = fetched.reviewPercent;
                        existing.reviewCount = fetched.reviewCount;
                        existing.fromWishlist = existing.fromWishlist || fromWishlist;
                        existing.purchased = existing.purchased || purchased;
                    }

                    addedOrUpdated++;
                    saveToFile();
                    System.out.printf("[%d/%d] Imported %-40s %s%s\n",
                            current,
                            totalToFetch,
                            truncateForProgress(fetched.name, 40),
                            purchased ? "[Library]" : "",
                            fromWishlist ? "[Wishlist]" : "");
                    sleepQuietly(IMPORT_DISPLAY_DELAY_MS);
                }
            }
            refreshActiveBuyList();
            System.out.println(GREEN + "Successfully synced " + addedOrUpdated + " games." + RESET);

        } catch (Exception e) {
            System.out.println("Network error: " + e.getMessage());
        }
    }

    private static void importPopularSteamGamesMenu() {
        System.out.println("\n--- IMPORT POPULAR STEAM GAMES ---");
        System.out.println("1. By current players");
        System.out.println("2. By good reviews");
        System.out.println("3. By both players and good reviews");
        System.out.print("> ");

        String choice = scanner.nextLine().trim();
        String mode;
        switch (choice) {
            case "1": mode = "players"; break;
            case "2": mode = "reviews"; break;
            case "3": mode = "both"; break;
            default:
                System.out.println("Canceled.");
                return;
        }

        System.out.print("Enter maximum number of games to import (default 100, max 10000; enter 0 for 10000): ");
        String countInput = scanner.nextLine().trim();
        int maxCount = 100;
        try {
            if (!countInput.isEmpty()) {
                maxCount = Integer.parseInt(countInput);
                if (maxCount <= 0) maxCount = 10000;
                if (maxCount > 10000) maxCount = 10000;
            }
        } catch (NumberFormatException e) {
            System.out.println(YELLOW + "Invalid count; using 100." + RESET);
        }

        importPopularSteamGames(mode, maxCount);
    }

    private static void importPopularSteamGames(String mode, int maxCount) {
        Set<String> ownedAppIds = new HashSet<>();
        if (!lastSteamId.isEmpty()) {
            ownedAppIds = getOwnedSteamAppIds();
        } else {
            System.out.println(YELLOW + "No Steam ID is configured, so ownership filtering will be skipped." + RESET);
        }

        int desiredCount = Math.max(1000, maxCount);
        int candidateTarget = Math.min(10000, Math.max(8000, desiredCount * 5));

        System.out.println("Gathering candidate games from Steam...");
        List<String> candidateIds = collectCandidateAppIds(mode, candidateTarget);
        if (candidateIds.isEmpty()) {
            System.out.println(YELLOW + "Steam did not return any usable app IDs for that import mode." + RESET);
            return;
        }

        System.out.println("Found " + candidateIds.size() + " unique candidate app IDs for import. Pulling details...");
        List<Game> imported = new ArrayList<>();
        int limit = desiredCount;
        int importedCount = 0;

        for (int i = 0; i < candidateIds.size(); i++) {
            String appid = candidateIds.get(i);
            if (importedCount >= limit) break;
            if (isAppIdOwned(appid, ownedAppIds)) {
                System.out.printf("[%d/%d] Skipping owned game %s.\n", importedCount + 1, limit, appid);
                continue;
            }

            int reviewPercent = 0;
            if (mode.equals("reviews") || mode.equals("both")) {
                reviewPercent = fetchReviewPercent(appid);
                if (reviewPercent > 0 && reviewPercent < 80) {
                    continue;
                }
            }

            Game existing = findGameByAppId(appid);
            Game g = fetchGameDetails(appid);
            if (g == null) continue;
            if (reviewPercent > 0) {
                g.reviewPercent = reviewPercent;
            } else {
                populateReviewMetadata(g);
            }

            String displayName = formatGameName(g.name != null ? g.name : appid);
            System.out.printf("[%d/%d] Adding %s...\n", importedCount + 1, limit, displayName);

            if (existing != null) {
                existing.name = formatGameName(g.name);
                existing.normal = g.normal;
                existing.sale = g.sale;
                existing.reviewPercent = g.reviewPercent;
                existing.reviewScore = g.reviewScore;
                existing.fromWishlist = true;
                imported.add(existing);
            } else {
                g.name = formatGameName(g.name);
                g.fromWishlist = true;
                basket.add(g);
                imported.add(g);
            }
            importedCount++;
            saveToFile();
        }

        if (imported.isEmpty()) {
            System.out.println(YELLOW + "No new popular Steam games matched the selected filter." + RESET);
            return;
        }

        refreshActiveBuyList();
        System.out.println(GREEN + "Imported " + imported.size() + " popular Steam games into the basket. Current basket size: " + basket.size() + RESET);
        for (Game g : imported) {
            System.out.println("- " + formatGameName(g.name) + " | $" + String.format(Locale.US, "%.2f", g.sale) + " (was $" + String.format(Locale.US, "%.2f", g.normal) + ")");
        }
    }

    private static List<String> collectCandidateAppIds(String mode, int maxCount) {
        List<String> candidateIds = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        List<List<String>> sourceBuckets = new ArrayList<>();

        if (mode.equals("players") || mode.equals("both")) {
            sourceBuckets.add(fetchPopularPlayerAppIds());
            sourceBuckets.add(fetchSearchAppIdsByFilter("topsellers", 1200));
            sourceBuckets.add(fetchSearchAppIdsByFilter("specials", 1200));
        }

        if (mode.equals("reviews") || mode.equals("both")) {
            sourceBuckets.add(fetchSearchAppIdsByFilter("topsellers", 1200));
            sourceBuckets.add(fetchSearchAppIdsByFilter("specials", 1200));
            sourceBuckets.add(fetchSearchAppIdsByFilter("new", 1200));
        }

        sourceBuckets.add(fetchFeaturedCategoryAppIds());
        sourceBuckets.add(getFallbackSteamAppIds());

        for (List<String> sourceIds : sourceBuckets) {
            for (String id : sourceIds) {
                if (seenIds.add(id)) {
                    candidateIds.add(id);
                }
                if (candidateIds.size() >= maxCount) {
                    return candidateIds;
                }
            }
        }

        return candidateIds;
    }

    private static String formatGameName(String name) {
        if (name == null) return "Unknown";
        String cleaned = name.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
        if (cleaned.isEmpty()) return "Unknown";
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 57) + "...";
        }
        return cleaned;
    }

    private static List<String> getFallbackSteamAppIds() {
        return Arrays.asList(
                "570", "730", "440", "578080", "271590", "1245620", "359550", "418370", "1811260", "1888930",
                "306130", "1172470", "413150", "252490", "230410", "582010", "1091500", "236390", "289070", "346110",
                "381210", "441910", "431960", "292030", "489830", "250900", "892970", "1286830", "1174180", "620",
                "221410", "257510", "588650", "374320", "976730", "105600", "219990", "300", "107410", "219150",
                "1533390", "200260", "262060", "427520", "500", "4000", "33230", "240", "444090", "200210",
                "43110", "22380", "236450", "480", "275850", "322330", "377160", "234140", "1118310", "645630",
                "1057090", "1435790", "1250410", "1274570", "1593500", "264710", "220", "1716740", "1222680", "1687950",
                "1472090", "1641650", "1782210", "1426210", "1675200", "1203220", "1506830", "1304930", "1522820", "1086940"
        );
    }

    private static void clearMenu() {
        System.out.println("\n--- CLEAR OPTIONS ---");
        System.out.println("1. Clear entire basket");
        System.out.println("2. Remove wishlist / Steam-imported games");
        System.out.print("> ");

        String choice = scanner.nextLine().trim();
        switch (choice) {
            case "1": clearEntireBasket(); break;
            case "2": clearWishlistGames(); break;
            default: System.out.println("Canceled."); break;
        }
    }

    private static void clearEntireBasket() {
        if (basket.isEmpty()) {
            System.out.println(YELLOW + "The basket is already empty." + RESET);
            return;
        }

        basket.clear();
        refreshActiveBuyList();
        System.out.println(GREEN + "Cleared the entire basket. Current basket size: " + basket.size() + RESET);
    }

    private static void clearWishlistGames() {
        int removed = 0;
        Iterator<Game> it = basket.iterator();
        while (it.hasNext()) {
            Game g = it.next();
            if (g.fromWishlist) {
                it.remove();
                removed++;
            }
        }
        refreshActiveBuyList();
        System.out.println(GREEN + "Removed " + removed + " wishlist/Steam imported games. Current basket size: " + basket.size() + RESET);
    }

    private static Game findGameByAppId(String appid) {
        for (Game g : basket) {
            if (appid.equals(g.appid)) {
                return g;
            }
        }
        return null;
    }

    private static List<String> fetchFeaturedCategoryAppIds() {
        List<String> ids = new ArrayList<>();
        try {
            HttpRequest req = buildSteamRequest("https://store.steampowered.com/api/featuredcategories/?cc=US&l=en");
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            Matcher appMatcher = Pattern.compile("(?:/app/|data-ds-appid=\"|\"id\":|\"appid\":)(\\d+)").matcher(res.body());
            while (appMatcher.find()) {
                ids.add(appMatcher.group(1));
                if (ids.size() >= 500) break;
            }
        } catch (Exception e) {
            System.out.println(YELLOW + "Could not fetch Steam featured categories: " + e.getMessage() + RESET);
        }
        return ids;
    }

    private static List<String> fetchPopularPlayerAppIds() {
        List<String> ids = new ArrayList<>();
        try {
            HttpRequest req = buildSteamRequest("https://store.steampowered.com/charts/mostplayed/?l=en&cc=US");
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            Matcher appMatcher = Pattern.compile("(?:/app/|data-ds-appid=\"|\"id\":|\"appid\":)(\\d+)").matcher(res.body());
            while (appMatcher.find()) {
                ids.add(appMatcher.group(1));
                if (ids.size() >= 1000) break;
            }
        } catch (Exception e) {
            System.out.println(YELLOW + "Could not fetch current player chart: " + e.getMessage() + RESET);
        }
        return ids;
    }

    private static List<String> fetchTopSellerAppIds() {
        return fetchSearchAppIdsByFilter("topsellers", 500);
    }

    private static List<String> fetchSpecialsAppIds() {
        return fetchSearchAppIdsByFilter("specials", 300);
    }

    private static List<String> fetchNewReleaseAppIds() {
        return fetchSearchAppIdsByFilter("new", 300);
    }

    private static List<String> fetchSearchAppIdsByFilter(String filter, int maxResults) {
        List<String> ids = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        int start = 0;
        int pages = 0;

        while (ids.size() < maxResults && pages < 500) {
            try {
                String url = "https://store.steampowered.com/search/results/?query&start=" + start + "&count=100&supportedlang=english&ndl=1&filter=" + filter;
                HttpRequest req = buildSteamRequest(url);
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                Matcher appMatcher = Pattern.compile("(?:/app/|data-ds-appid=\"|\"id\":|\"appid\":)(\\d+)").matcher(res.body());
                boolean foundAny = false;
                while (appMatcher.find()) {
                    foundAny = true;
                    String appid = appMatcher.group(1);
                    if (seenIds.add(appid)) {
                        ids.add(appid);
                        if (ids.size() >= maxResults) break;
                    }
                }
                if (!foundAny) break;
                start += 100;
                pages++;
            } catch (Exception e) {
                System.out.println(YELLOW + "Could not fetch Steam search results for filter '" + filter + "': " + e.getMessage() + RESET);
                break;
            }
        }
        return ids;
    }

    private static Game fetchGameDetails(String appid) {
        try {
            HttpRequest req = buildSteamRequest("https://store.steampowered.com/api/appdetails?appids=" + appid + "&filters=price_overview,basic");
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            return parseGameDetails(appid, res.body());
        } catch (Exception e) {
            return null;
        }
    }

    private static CompletableFuture<Map<String, Game>> fetchGameDetailsAndReviewsBatchAsync(List<String> appids, Set<String> wishlistAppIds, Set<String> libraryAppIds) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Game> fetchedGames = fetchGameDetailsBatch(appids);
            List<Game> reviewTargets = new ArrayList<>();
            for (String appid : appids) {
                Game fetched = fetchedGames.get(appid);
                boolean needsReviews = wishlistAppIds.contains(appid) || !libraryAppIds.contains(appid);
                if (fetched != null && needsReviews) reviewTargets.add(fetched);
            }
            populateReviewMetadataBatch(reviewTargets);
            return fetchedGames;
        });
    }

    private static Map<String, Game> fetchGameDetailsBatch(List<String> appids) {
        Map<String, Game> games = Collections.synchronizedMap(new LinkedHashMap<>());
        if (appids == null || appids.isEmpty()) return games;

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (String appid : appids) {
                HttpRequest req = buildSteamRequest("https://store.steampowered.com/api/appdetails?appids=" + appid + "&filters=price_overview,basic");
                CompletableFuture<Void> future = client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                        .orTimeout(HTTP_TIMEOUT.toSeconds() + 2, TimeUnit.SECONDS)
                        .thenApply(HttpResponse::body)
                        .thenApply(body -> {
                            synchronized (SteamOptimizer.class) {
                                return parseGameDetails(appid, body);
                            }
                        })
                        .thenAccept(game -> {
                            if (game != null) games.put(appid, game);
                        })
                        .exceptionally(e -> null);
                futures.add(future);
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            for (String appid : appids) {
                Game g = fetchGameDetails(appid);
                if (g != null) games.put(appid, g);
            }
        }
        return games;
    }

    private static Game parseGameDetails(String appid, String body) {
        if (body == null || body.isEmpty()) return null;

        String name = extractString(body, "\"name\":\"([^\"]+)\"");
        if (name == null) return null;

        Game g = new Game(idCounter++, appid, name);
        String initialStr = extractString(body, "\"initial\":(\\d+)");
        String finalStr = extractString(body, "\"final\":(\\d+)");
        if (initialStr != null) g.normal = Double.parseDouble(initialStr) / 100.0;
        if (finalStr != null) g.sale = Double.parseDouble(finalStr) / 100.0;
        return g;
    }

    private static String extractJsonObjectForKey(String source, String key) {
        if (source == null || key == null) return null;

        int keyIndex = source.indexOf("\"" + key + "\"");
        if (keyIndex < 0) return null;

        int colonIndex = source.indexOf(':', keyIndex);
        int start = source.indexOf('{', colonIndex);
        if (colonIndex < 0 || start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(start, i + 1);
            }
        }
        return null;
    }

    private static int fetchReviewPercent(String appid) {
        try {
            HttpRequest req = buildSteamRequest("https://store.steampowered.com/appreviews/" + appid + "?json=1&cursor=*&num_per_page=0");
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            String totalReviews = extractString(res.body(), "\"total_reviews\":(\\d+)");
            String totalPositive = extractString(res.body(), "\"total_positive\":(\\d+)");
            if (totalReviews != null && totalPositive != null) {
                double total = Double.parseDouble(totalReviews);
                double pos = Double.parseDouble(totalPositive);
                if (total > 0) return (int) Math.round((pos / total) * 100);
            }
        } catch (Exception e) {
            // Ignore review lookup failures and keep the game if other data worked.
        }
        return 0;
    }

    private static HttpRequest buildSteamRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0")
                .build();
    }

    private static void ensureSteamApiKeyForLibrary() {
        if (!steamApiKey.isEmpty()) return;

        String envKey = System.getenv("STEAM_API_KEY");
        if (envKey != null && !envKey.trim().isEmpty()) {
            steamApiKey = envKey.trim();
            saveToFile();
            return;
        }

        System.out.println(YELLOW + "Steam library import usually requires a Steam Web API key." + RESET);
        System.out.print("Enter Steam Web API key for library import, or press Enter to try public fallback only: ");
        String input = scanner.nextLine().trim();
        if (!input.isEmpty()) {
            steamApiKey = input;
            ownedSteamCacheLoaded = false;
            ownedSteamCacheSteamId = "";
            saveToFile();
        }
    }

    private static Set<String> getOwnedSteamAppIds() {
        if (!lastSteamId.isEmpty() && (!ownedSteamCacheLoaded || !ownedSteamCacheSteamId.equals(lastSteamId))) {
            ownedSteamCacheLoaded = true;
            ownedSteamCacheSteamId = lastSteamId;
            ownedSteamAppIdsCache = fetchOwnedSteamAppIds();
        }
        if (lastSteamId.isEmpty()) {
            ownedSteamAppIdsCache.clear();
            ownedSteamCacheLoaded = true;
            ownedSteamCacheSteamId = "";
        }
        return ownedSteamAppIdsCache;
    }

    private static boolean isAppIdOwned(String appid, Set<String> ownedAppIds) {
        return appid != null
                && !appid.isEmpty()
                && (ownedAppIds.contains(appid) || ownedGames.contains(appid));
    }

    private static boolean isGameOwned(Game game, Set<String> ownedAppIds) {
        return game != null && isAppIdOwned(game.appid, ownedAppIds);
    }

    private static void populateReviewMetadata(Game game) {
        if (game == null || game.appid == null || game.appid.isEmpty()) return;
        try {
            HttpRequest rReq = buildSteamRequest("https://store.steampowered.com/appreviews/" + game.appid + "?json=1&cursor=*&num_per_page=0");
            HttpResponse<String> rRes = client.send(rReq, HttpResponse.BodyHandlers.ofString());
            applyReviewMetadata(game, rRes.body());
        } catch (Exception e) {
            // Leave the review metadata as-is if the endpoint is unavailable.
        }
    }

    private static void populateReviewMetadataBatch(List<Game> games) {
        if (games == null || games.isEmpty()) return;

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Game game : games) {
            if (game == null || game.appid == null || game.appid.isEmpty()) continue;

            HttpRequest req = buildSteamRequest("https://store.steampowered.com/appreviews/" + game.appid + "?json=1&cursor=*&num_per_page=0");
            CompletableFuture<Void> future = client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(HTTP_TIMEOUT.toSeconds() + 2, TimeUnit.SECONDS)
                    .thenApply(HttpResponse::body)
                    .thenAccept(body -> applyReviewMetadata(game, body))
                    .exceptionally(e -> null);
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private static void applyReviewMetadata(Game game, String body) {
        if (game == null || body == null || body.isEmpty()) return;

        String revDesc = extractString(body, "\"review_score_desc\":\"([^\"]+)\"");
        String revTotalStr = extractString(body, "\"total_reviews\":(\\d+)");
        String revPosStr = extractString(body, "\"total_positive\":(\\d+)");

        if (revDesc != null && !revDesc.isEmpty()) game.reviewScore = revDesc;
        if (revTotalStr != null && revPosStr != null) {
            double total = Double.parseDouble(revTotalStr);
            double pos = Double.parseDouble(revPosStr);
            if (total > 0) game.reviewPercent = (int) Math.round((pos / total) * 100);
        }
    }

    private static Set<String> fetchOwnedSteamAppIds() {
        Set<String> owned = new LinkedHashSet<>();

        if (!steamApiKey.isEmpty()) {
            owned.addAll(fetchOwnedSteamAppIdsFromWebApi());
            if (!owned.isEmpty()) return owned;
        }

        List<String> candidateUrls = new ArrayList<>();

        if (lastSteamId.matches("\\d+")) {
            candidateUrls.add("https://steamcommunity.com/profiles/" + lastSteamId + "/games?tab=all&xml=1");
            candidateUrls.add("https://steamcommunity.com/profiles/" + lastSteamId + "/games/?tab=all&xml=1");
            candidateUrls.add("https://steamcommunity.com/profiles/" + lastSteamId + "/games/?tab=all");
            candidateUrls.add("https://steamcommunity.com/profiles/" + lastSteamId + "/games?tab=all");
        } else if (lastSteamId.startsWith("http")) {
            String base = lastSteamId.replaceAll("/?$", "");
            candidateUrls.add(base + "/games?tab=all&xml=1");
            candidateUrls.add(base + "/games/?tab=all&xml=1");
            candidateUrls.add(base + "/games/?tab=all");
        } else {
            candidateUrls.add("https://steamcommunity.com/id/" + lastSteamId + "/games?tab=all&xml=1");
            candidateUrls.add("https://steamcommunity.com/id/" + lastSteamId + "/games/?tab=all&xml=1");
            candidateUrls.add("https://steamcommunity.com/id/" + lastSteamId + "/games/?tab=all");
        }

        for (String url : candidateUrls) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(HTTP_TIMEOUT)
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build();
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                String body = res.body();

                if (body == null || body.isEmpty()) continue;

                Matcher appIdMatcher = Pattern.compile("<appID>(\\d+)</appID>").matcher(body);
                while (appIdMatcher.find()) {
                    owned.add(appIdMatcher.group(1));
                }

                if (owned.isEmpty()) {
                    Matcher jsonMatcher = Pattern.compile("\\\"appid\\\":\\s*(\\d+)").matcher(body);
                    while (jsonMatcher.find()) {
                        owned.add(jsonMatcher.group(1));
                    }
                }

                if (owned.isEmpty()) {
                    Matcher dataMatcher = Pattern.compile("data-appid=\\\"(\\d+)\\\"").matcher(body);
                    while (dataMatcher.find()) {
                        owned.add(dataMatcher.group(1));
                    }
                }

                if (owned.isEmpty()) {
                    Matcher htmlMatcher = Pattern.compile("(?:/app/|/sub/)(\\d+)").matcher(body);
                    while (htmlMatcher.find()) {
                        owned.add(htmlMatcher.group(1));
                    }
                }

                if (!owned.isEmpty()) break;
            } catch (Exception e) {
                // Try the next URL if one variant fails.
            }
        }

        if (owned.isEmpty()) {
            System.out.println(YELLOW + "Could not read your owned games list from Steam. Make sure your profile is public and the Steam ID is correct." + RESET);
        }
        return owned;
    }

    private static Set<String> fetchOwnedSteamAppIdsFromWebApi() {
        Set<String> owned = new LinkedHashSet<>();
        try {
            String encodedKey = URLEncoder.encode(steamApiKey, StandardCharsets.UTF_8);
            String encodedSteamId = URLEncoder.encode(lastSteamId, StandardCharsets.UTF_8);
            String url = "https://api.steampowered.com/IPlayerService/GetOwnedGames/v0001/"
                    + "?key=" + encodedKey
                    + "&steamid=" + encodedSteamId
                    + "&format=json"
                    + "&include_appinfo=0"
                    + "&include_played_free_games=1";

            HttpRequest req = buildSteamRequest(url);
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                System.out.println(YELLOW + "Steam rejected the API key for owned-games import. Check Settings > Steam API Key." + RESET);
                return owned;
            }

            Matcher appIdMatcher = Pattern.compile("\"appid\":\\s*(\\d+)").matcher(res.body());
            while (appIdMatcher.find()) {
                owned.add(appIdMatcher.group(1));
            }

            if (!owned.isEmpty()) {
                System.out.println(GREEN + "Loaded " + owned.size() + " owned Steam app IDs from the Steam Web API." + RESET);
            }
        } catch (Exception e) {
            System.out.println(YELLOW + "Could not fetch owned games from Steam Web API: " + e.getMessage() + RESET);
        }
        return owned;
    }

   private static void generateCartScript() {
        boolean hasSkippedGames = false;
        for (Game g : basket) {
            if (g.skip) {
                hasSkippedGames = true;
                break;
            }
        }

        if (hasSkippedGames) {
            System.out.println(YELLOW + "\nYou have games currently marked as [SKIP]." + RESET);
            System.out.print("Reset all skipped games before generating the cart script? (y/N): ");
            String resetChoice = scanner.nextLine().trim().toLowerCase();
            if (resetChoice.equals("y") || resetChoice.equals("yes")) {
                for (Game g : basket) {
                    g.skip = false;
                }
                runOptimizationAlgorithm();
                saveToFile();
                System.out.println(GREEN + "Skipped games reset and the buy list was recalculated." + RESET);
            }
        }

        List<String> buyIds = new ArrayList<>();
        for (Game g : basket) {
            if (g.picked && !g.appid.equals("manual")) {
                buyIds.add(g.appid);
            }
        }

        if (buyIds.isEmpty()) {
            System.out.println("No Steam games currently marked as [BUY]. Run the Optimizer first.");
            return;
        }

        System.out.println("\n=== AUTO-CART SCRIPT ===");
        System.out.println("1. Log into store.steampowered.com in your web browser.");
        System.out.println("2. Press F12 to open Developer Tools, and click the 'Console' tab.");
        System.out.println("3. Copy the entire code block below, paste it into the console, and hit Enter.\n");
        
        System.out.println("(async () => {");
        System.out.println("  const appids = [" + String.join(", ", buyIds) + "];");
        System.out.println("  let added = 0;");
        System.out.println("  ");
        System.out.println("  if (typeof g_sessionID === 'undefined') {");
        System.out.println("      console.error(\"❌ ERROR: g_sessionID is missing. Make sure you are on a store.steampowered.com page and logged in.\");");
        System.out.println("      return;");
        System.out.println("  }");
        System.out.println("  ");
        System.out.println("  console.log(\"Starting cart injection...\");");
        System.out.println("  ");
        System.out.println("  for (let appid of appids) {");
        System.out.println("    try {");
        System.out.println("      // 1. Ask Steam for the correct Package ID (SubID)");
        System.out.println("      const detailsRes = await fetch(`https://store.steampowered.com/api/appdetails?appids=${appid}&filters=packages`);");
        System.out.println("      const details = await detailsRes.json();");
        System.out.println("      ");
        System.out.println("      if (!details[appid].success || !details[appid].data.packages || details[appid].data.packages.length === 0) {");
        System.out.println("          console.warn(`⚠️ Could not find a purchasable package for App ${appid}. Skipping.`);");
        System.out.println("          continue;");
        System.out.println("      }");
        System.out.println("      ");
        System.out.println("      const subid = details[appid].data.packages[0];");
        System.out.println("      ");
        System.out.println("      // 2. Add the exact SubID to the cart");
        System.out.println("      const params = new URLSearchParams();");
        System.out.println("      params.append('action', 'add_to_cart');");
        System.out.println("      params.append('sessionid', g_sessionID);");
        System.out.println("      params.append('subid', subid);");
        System.out.println("      ");
        System.out.println("      const res = await fetch('https://store.steampowered.com/cart/', {");
        System.out.println("        method: 'POST',");
        System.out.println("        headers: {");
        System.out.println("          'Content-Type': 'application/x-www-form-urlencoded'");
        System.out.println("        },");
        System.out.println("        body: params,");
        System.out.println("        credentials: 'include'");
        System.out.println("      });");
        System.out.println("      ");
        System.out.println("      added++;");
        System.out.println("      console.log(`%c✔ Added App ${appid} (SubID: ${subid})`, 'color: #4cd2a0; font-weight: bold;');");
        System.out.println("      ");
        System.out.println("    } catch(e) { ");
        System.out.println("      console.error(`❌ Failed on app ${appid}`, e); ");
        System.out.println("    }");
        System.out.println("    ");
        System.out.println("    await new Promise(r => setTimeout(r, 500));");
        System.out.println("  }");
        System.out.println("  ");
        System.out.println("  console.log(`Done! Successfully processed ${added} items.`);");
        System.out.println("  window.location.href = 'https://store.steampowered.com/cart/';");
        System.out.println("})();");

        System.out.println("\nPress Enter to return to menu.");
        scanner.nextLine();
    }

    private static void addManualGame() {
        System.out.print("Game Name: ");
        String name = scanner.nextLine().trim();
        System.out.print("List Price (Normal): ");
        double norm = Double.parseDouble(scanner.nextLine().trim());
        System.out.print("Sale Price: ");
        double sale = Double.parseDouble(scanner.nextLine().trim());
        
        Game g = new Game(idCounter++, "manual", name);
        g.normal = norm;
        g.sale = sale;
        basket.add(g);
        refreshActiveBuyList();
        System.out.println("Game added.");
    }

    private static void manageBasket() {
        if (basket.isEmpty()) {
            System.out.println("Basket is empty.");
            return;
        }
        
        basket.sort((a, b) -> {
            if (a.skip != b.skip) return a.skip ? 1 : -1;
            if (a.picked != b.picked) return a.picked ? -1 : 1; 
            return Double.compare(b.getSavings(), a.getSavings());
        });

        System.out.println("\n--- FULL BASKET ---");
        for (Game g : basket) {
            System.out.println(g);
        }

        System.out.println("\nEnter an ID to toggle SKIP status, 'skipall' to skip every game, 'reset' to clear all skips, 'purchased <id>' to mark a game as purchased, 'remove purchased' to delete purchased games, 'clearwishlist' to remove wishlist games, 'clear' to empty basket, or press Enter to return.");
        System.out.print("> ");
        String input = scanner.nextLine().trim();
        
        if (input.equalsIgnoreCase("clear")) {
            clearEntireBasket();
        } else if (input.equalsIgnoreCase("clearwishlist")) {
            clearWishlistGames();
        } else if (input.equalsIgnoreCase("remove purchased")) {
            removePurchasedGames();
        } else if (input.equalsIgnoreCase("skipall")) {
            boolean changed = false;
            for (Game g : basket) {
                if (!g.skip) {
                    g.skip = true;
                    changed = true;
                }
            }
            refreshActiveBuyList();
            System.out.println(changed ? "All games marked as skipped." : "All games were already skipped.");
        } else if (input.equalsIgnoreCase("reset")) {
            for (Game g : basket) {
                g.skip = false;
            }
            refreshActiveBuyList();
            System.out.println("All skips cleared.");
        } else if (!input.isEmpty()) {
            if (input.startsWith("purchased ")) {
                try {
                    int targetId = Integer.parseInt(input.substring("purchased ".length()).trim());
                    for (Game g : basket) {
                        if (g.id == targetId) {
                            g.purchased = !g.purchased;
                            refreshActiveBuyList();
                            System.out.println(g.name + " purchased status set to " + g.purchased);
                            break;
                        }
                    }
                } catch (NumberFormatException ignored) {}
            } else {
                try {
                    int targetId = Integer.parseInt(input);
                    for (Game g : basket) {
                        if (g.id == targetId) {
                            g.skip = !g.skip;
                            refreshActiveBuyList();
                            System.out.println(g.name + " skip status set to " + g.skip);
                            break;
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    // --- Optimization Logic & Interactive Menu ---
    
    private static void optimizeAndReview() {
        if (budget <= 0 || basket.isEmpty()) {
            System.out.println(YELLOW + "Need a valid budget (check Settings) and items in the basket to optimize." + RESET);
            return;
        }

        // Run the math silently first
        runOptimizationAlgorithm();
        saveToFile();

        // Start the interactive loop
        while (true) {
            double totalSpend = 0;
            double totalSaved = 0;
            List<Game> recommended = new ArrayList<>();

            for (Game g : basket) {
                if (g.picked) {
                    recommended.add(g);
                    totalSpend += g.sale;
                    totalSaved += g.getSavings();
                }
            }

            System.out.println("\n" + CYAN + BOLD + "=== RECOMMENDED BUY LIST ===" + RESET);
            if (recommended.isEmpty()) {
                System.out.println(YELLOW + "No games fit your budget or criteria." + RESET);
            } else {
                for (Game g : recommended) {
                    System.out.println(g);
                }
            }

            System.out.println("--------------------------------------------------");
            System.out.println("tripple check the list as i cannot right now gurantee that a game you own will not be on the list if you imported steam games");
            System.out.printf("Total Spend: $%s%.2f%s (%.0f%% of $%.2f budget)\n", GREEN, totalSpend, RESET, (totalSpend / budget) * 100, budget);
            System.out.printf("Total Saved: $%s%.2f%s\n", GREEN, totalSaved, RESET);
            System.out.println("--------------------------------------------------");
            System.out.println("Type an " + BOLD + "ID" + RESET + " to skip a game and automatically replace it.");
            System.out.println("Type " + BOLD + "'skipall'" + RESET + " to skip every current recommendation and recalculate.");
            System.out.println("Type " + BOLD + "'reset'" + RESET + " to clear all skips and recalculate.");
            System.out.println("Type " + BOLD + "'remove purchased'" + RESET + " to delete any purchased games.");
            System.out.println("Type " + BOLD + "'cart'" + RESET + " to generate the checkout script.");
            System.out.println("Type " + BOLD + "'back'" + RESET + " to return to the main menu.");
            System.out.print("> ");

            String input = scanner.nextLine().trim().toLowerCase();

            if (input.equals("back") || input.isEmpty()) {
                return;
            } else if (input.equals("cart")) {
                generateCartScript();
            } else if (input.equals("skipall") || input.equals("skip all")) {
                boolean changed = false;
                for (Game g : recommended) {
                    if (!g.skip) {
                        g.skip = true;
                        changed = true;
                    }
                }
                if (changed) {
                    System.out.println(YELLOW + "Skipped all current recommendations. Recalculating basket..." + RESET);
                    runOptimizationAlgorithm();
                    saveToFile();
                } else {
                    System.out.println(YELLOW + "All current recommendations are already skipped." + RESET);
                }
            } else if (input.equals("reset")) {
                for (Game g : basket) {
                    g.skip = false;
                }
                System.out.println(YELLOW + "All skips cleared. Recalculating basket..." + RESET);
                runOptimizationAlgorithm();
                saveToFile();
            } else if (input.equals("remove purchased")) {
                removePurchasedGames();
            } else {
                try {
                    int targetId = Integer.parseInt(input);
                    boolean found = false;
                    for (Game g : recommended) {
                        if (g.id == targetId) {
                            g.skip = true;
                            found = true;
                            System.out.println(YELLOW + "Skipped '" + g.name + "'. Recalculating basket..." + RESET);
                            runOptimizationAlgorithm(); // Math runs again!
                            saveToFile();
                            break;
                        }
                    }
                    if (!found) {
                        System.out.println(YELLOW + "That ID is not in the current buy list." + RESET);
                    }
                } catch (NumberFormatException e) {
                    System.out.println(YELLOW + "Invalid input." + RESET);
                }
            }
        }
    }

    private static void refreshActiveBuyList() {
        for (Game g : basket) g.picked = false;
        if (budget > 0 && !basket.isEmpty()) {
            runOptimizationAlgorithm();
        }
        saveToFile();
    }

    private static void runOptimizationAlgorithm() {
        for (Game g : basket) g.picked = false;

        loadOwnedGames();

        int budgetCents = (int) Math.round(budget * 100);
        List<WeightedItem> candidates = new ArrayList<>();

        Set<String> ownedAppIds = getOwnedSteamAppIds();
        for (Game g : basket) {
            if (g.skip || g.purchased || isGameOwned(g, ownedAppIds) || g.sale < 0 || g.sale == g.normal) continue;
            int weight = (int) Math.round(g.sale * 100);
            if (weight > budgetCents) continue;

            int value = 0;
            if (objective.equals("bang")) {
                double savingsRaw = g.getSavings();
                double ratingFactor = 0.5;
                if (g.reviewPercent > 0) {
                    if (g.reviewPercent < 50) ratingFactor = (g.reviewPercent / 100.0) * 0.3; 
                    else ratingFactor = g.reviewPercent / 100.0;
                }
                value = (int) Math.round(savingsRaw * ratingFactor * 100);
            } else if (objective.equals("savings")) {
                value = (int) Math.round(g.getSavings() * 100);
            } else if (objective.equals("value")) {
                value = (int) Math.round(g.normal * 100);
            } else {
                value = 1; 
            }
            candidates.add(new WeightedItem(g, weight, value));
        }

        long complexity = (long) candidates.size() * budgetCents;
        List<WeightedItem> chosen;

        if (candidates.isEmpty()) {
            chosen = new ArrayList<>();
        } else if (candidates.size() <= 25) {
            chosen = knapsackBruteForce(candidates, budgetCents);
        } else if (complexity > 0 && complexity <= 10_000_000) {
            chosen = knapsackExact(candidates, budgetCents);
        } else {
            chosen = knapsackGreedy(candidates, budgetCents);
        }

        for (WeightedItem w : chosen) {
            if (isGameOwned(w.game, ownedAppIds)) {
                System.out.println(YELLOW + "Removing owned game from recommendation list: " + w.game.name + RESET);
                continue;
            }
            w.game.picked = true;
        }
    }

    private static void removePurchasedGames() {
        int removed = 0;
        Iterator<Game> it = basket.iterator();
        while (it.hasNext()) {
            Game g = it.next();
            if (g.purchased) {
                it.remove();
                removed++;
            }
        }
        refreshActiveBuyList();
        System.out.println(GREEN + "Removed " + removed + " purchased games." + RESET);
    }

    // --- Algorithms ---
    private static List<WeightedItem> knapsackBruteForce(List<WeightedItem> items, int capacity) {
        List<WeightedItem> best = new ArrayList<>();
        int bestValue = -1;
        int n = items.size();

        for (int mask = 0; mask < (1 << n); mask++) {
            int weight = 0;
            int value = 0;
            List<WeightedItem> chosen = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    WeightedItem item = items.get(i);
                    weight += item.weight;
                    value += item.value;
                    chosen.add(item);
                }
            }
            if (weight <= capacity && value > bestValue) {
                bestValue = value;
                best = chosen;
            }
        }
        return best;
    }

    private static List<WeightedItem> knapsackExact(List<WeightedItem> items, int capacity) {
        int n = items.size();
        int[][] dp = new int[n + 1][capacity + 1];

        for (int i = 1; i <= n; i++) {
            WeightedItem item = items.get(i - 1);
            for (int w = 0; w <= capacity; w++) {
                int best = dp[i - 1][w];
                if (item.weight <= w) {
                    int alt = dp[i - 1][w - item.weight] + item.value;
                    if (alt > best) best = alt;
                }
                dp[i][w] = best;
            }
        }

        List<WeightedItem> result = new ArrayList<>();
        int w = capacity;
        for (int i = n; i > 0; i--) {
            if (dp[i][w] != dp[i - 1][w]) {
                WeightedItem item = items.get(i - 1);
                result.add(item);
                w -= item.weight;
            }
        }
        return result;
    }

    private static List<WeightedItem> knapsackGreedy(List<WeightedItem> items, int capacity) {
        items.sort((a, b) -> Double.compare(
                (double) b.value / Math.max(1, b.weight),
                (double) a.value / Math.max(1, a.weight)
        ));

        List<WeightedItem> result = new ArrayList<>();
        int rem = capacity;
        for (WeightedItem item : items) {
            if (item.weight <= rem) {
                result.add(item);
                rem -= item.weight;
            }
        }
        return result;
    }

    // --- Utilities ---
    private static String extractString(String source, String regex) {
        Matcher m = Pattern.compile(regex).matcher(source);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String truncateForProgress(String text, int maxLength) {
        if (text == null || text.isEmpty()) return "Unknown";
        if (text.length() <= maxLength) return text;
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String getObjectiveName() {
        switch(objective) {
            case "bang": return "Bang for Buck";
            case "savings": return "Max Savings";
            case "value": return "Max List Value";
            case "count": return "Max Game Count";
            default: return "Unknown";
        }
    }
}
