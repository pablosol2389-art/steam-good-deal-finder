import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
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

        public Game(int id, String appid, String name) {
            this.id = id;
            this.appid = appid;
            this.name = name;
            this.skip = false;
            this.picked = false;
            this.reviewScore = "Unknown";
            this.reviewPercent = 70; 
            this.reviewCount = 0;
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
            if (picked) { status = "[BUY] "; color = GREEN + BOLD; }
            else if (skip) { status = "[SKIP]"; color = YELLOW; }

            String text = String.format("%s ID:%02d | %-30s | Rev: %3d%% (%-13s) | Price: $%5.2f -> $%5.2f (Save $%5.2f, %3.0f%%)",
                    status, id, truncate(name, 30), reviewPercent, reviewScore, normal, sale, getSavings(), getDiscountPct());
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
    private static Scanner scanner = new Scanner(System.in);
    private static HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    // --- Main Loop ---
    public static void main(String[] args) {
        System.out.println("=========================================");
        System.out.println("   STEAM SALE BASKET PLANNER (TERMINAL)  ");
        System.out.println("=========================================");
        
        loadFromFile();

        while (true) {
            System.out.println("\n--- MAIN MENU ---");
            System.out.printf("Budget: $%.2f | Objective: %s | Games Loaded: %d\n", budget, getObjectiveName(), basket.size());
            System.out.println("1. Quick Sync Wishlist (Only add new games)");
            System.out.println("2. Deep Sync Wishlist (Update all prices - SLOW)");
            System.out.println("3. Add Game Manually");
            System.out.println("4. View/Manage Full Basket");
            System.out.println(CYAN + BOLD + "5. OPTIMIZE & INTERACTIVE BUY MENU" + RESET);
            System.out.println("6. Generate 'Add to Cart' Script");
            System.out.println("7. Settings (Change SteamID, Budget, Objective)");
            System.out.println("0. Exit");
            System.out.print("> ");

            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1": fetchWishlist(false); break;
                case "2": fetchWishlist(true); break;
                case "3": addManualGame(); break;
                case "4": manageBasket(); break;
                case "5": optimizeAndReview(); break;
                case "6": generateCartScript(); break;
                case "7": openSettings(); break;
                case "0": 
                    saveToFile(); 
                    System.out.println("Saved. Exiting..."); 
                    return;
                default: System.out.println("Invalid choice.");
            }
        }
    }

    // --- Persistence ---
    private static void saveToFile() {
        // FIXED: Enforcing UTF-8 encoding when writing to prevent mangled symbols
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(SAVE_FILE), StandardCharsets.UTF_8))) {
            out.println(lastSteamId + "|" + budget + "|" + objective);
            for (Game g : basket) {
                out.printf("%s\t%s\t%f\t%f\t%b\t%s\t%d\t%d\n",
                        g.appid, g.name.replace("\t", " "), g.normal, g.sale, g.skip, g.reviewScore, g.reviewPercent, g.reviewCount);
            }
        } catch (IOException e) {
            System.out.println("Failed to save basket: " + e.getMessage());
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
                    basket.add(g);
                }
            }
            System.out.println(GREEN + "Successfully loaded " + basket.size() + " games from local save." + RESET);
        } catch (Exception e) {
            System.out.println("Error loading save file. Starting fresh.");
        }
    }

    // --- Settings Menu ---
    private static void openSettings() {
        while(true) {
            System.out.println("\n--- SETTINGS ---");
            System.out.println("1. SteamID64 : " + (lastSteamId.isEmpty() ? "Not Set" : lastSteamId));
            System.out.println("2. Budget    : $" + budget);
            System.out.println("3. Objective : " + getObjectiveName());
            System.out.println("0. Return to Main Menu");
            System.out.print("Select item to modify > ");
            
            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1":
                    System.out.print("Enter new SteamID64: ");
                    String newId = scanner.nextLine().trim();
                    if (!newId.isEmpty()) lastSteamId = newId;
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
                case "0":
                    saveToFile();
                    return;
                default:
                    System.out.println(YELLOW + "Invalid choice." + RESET);
            }
        }
    }

    // --- Features ---
    private static void fetchWishlist(boolean forceUpdateAll) {
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

        System.out.println("Checking Steam profile for ID: " + lastSteamId + " ...");
        try {
            HttpRequest wlReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.steampowered.com/IWishlistService/GetWishlist/v1?steamid=" + lastSteamId))
                    .build();
            HttpResponse<String> wlRes = client.send(wlReq, HttpResponse.BodyHandlers.ofString());
            
            Matcher mId = Pattern.compile("\"appid\":\\s*(\\d+)").matcher(wlRes.body());
            List<String> wishlistAppIds = new ArrayList<>();
            while (mId.find()) wishlistAppIds.add(mId.group(1));

            if (wishlistAppIds.isEmpty()) {
                System.out.println(YELLOW + "No games found. Make sure your profile is public." + RESET);
                return;
            }

            List<String> idsToFetch = new ArrayList<>();
            Set<String> existingIds = new HashSet<>();
            for (Game g : basket) existingIds.add(g.appid);

            for (String wid : wishlistAppIds) {
                if (forceUpdateAll || !existingIds.contains(wid)) {
                    idsToFetch.add(wid);
                }
            }

            if (idsToFetch.isEmpty()) {
                System.out.println(GREEN + "Local list is already up to date! No new games found." + RESET);
                return;
            }

            System.out.printf("Fetching pricing and review data for %d games...\n", idsToFetch.size());
            int addedOrUpdated = 0;

            for (int i = 0; i < idsToFetch.size(); i++) {
                String appid = idsToFetch.get(i);
                if (i % 5 == 0 && i > 0) System.out.printf("... %d / %d processed\n", i, idsToFetch.size());

                HttpRequest dReq = HttpRequest.newBuilder()
                        .uri(URI.create("https://store.steampowered.com/api/appdetails?appids=" + appid + "&filters=price_overview,basic"))
                        .build();
                HttpResponse<String> dRes = client.send(dReq, HttpResponse.BodyHandlers.ofString());
                String dBody = dRes.body();

                String name = extractString(dBody, "\"name\":\"([^\"]+)\"");
                if (name == null) continue;

                Game g = basket.stream().filter(b -> b.appid.equals(appid)).findFirst().orElse(null);
                if (g == null) {
                    g = new Game(idCounter++, appid, name);
                    basket.add(g);
                }

                String initialStr = extractString(dBody, "\"initial\":(\\d+)");
                String finalStr = extractString(dBody, "\"final\":(\\d+)");
                if (initialStr != null) g.normal = Double.parseDouble(initialStr) / 100.0;
                if (finalStr != null) g.sale = Double.parseDouble(finalStr) / 100.0;

                HttpRequest rReq = HttpRequest.newBuilder()
                        .uri(URI.create("https://store.steampowered.com/appreviews/" + appid + "?json=1&cursor=*&num_per_page=0"))
                        .build();
                HttpResponse<String> rRes = client.send(rReq, HttpResponse.BodyHandlers.ofString());
                
                String revDesc = extractString(rRes.body(), "\"review_score_desc\":\"([^\"]+)\"");
                String revTotalStr = extractString(rRes.body(), "\"total_reviews\":(\\d+)");
                String revPosStr = extractString(rRes.body(), "\"total_positive\":(\\d+)");

                if (revDesc != null) g.reviewScore = revDesc;
                if (revTotalStr != null && revPosStr != null) {
                    double total = Double.parseDouble(revTotalStr);
                    double pos = Double.parseDouble(revPosStr);
                    if (total > 0) g.reviewPercent = (int) Math.round((pos / total) * 100);
                }

                addedOrUpdated++;
                saveToFile(); 
                Thread.sleep(1200); 
            }
            System.out.println(GREEN + "Successfully synced " + addedOrUpdated + " games." + RESET);

        } catch (Exception e) {
            System.out.println("Network error: " + e.getMessage());
        }
    }

   private static void generateCartScript() {
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
        saveToFile();
        System.out.println("Game added.");
    }

    private static void manageBasket() {
        if (basket.isEmpty()) {
            System.out.println("Basket is empty.");
            return;
        }
        
        basket.sort((a, b) -> {
            if (a.picked != b.picked) return a.picked ? -1 : 1; 
            return Double.compare(b.getSavings(), a.getSavings());
        });

        System.out.println("\n--- FULL BASKET ---");
        for (Game g : basket) {
            System.out.println(g);
        }

        System.out.println("\nEnter an ID to toggle SKIP status, 'clear' to empty basket, or press Enter to return.");
        System.out.print("> ");
        String input = scanner.nextLine().trim();
        
        if (input.equalsIgnoreCase("clear")) {
            basket.clear();
            saveToFile();
            System.out.println("Basket cleared.");
        } else if (!input.isEmpty()) {
            try {
                int targetId = Integer.parseInt(input);
                for (Game g : basket) {
                    if (g.id == targetId) {
                        g.skip = !g.skip;
                        saveToFile();
                        System.out.println(g.name + " skip status set to " + g.skip);
                        break;
                    }
                }
            } catch (NumberFormatException ignored) {}
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
            System.out.printf("Total Spend: $%s%.2f%s (%.0f%% of $%.2f budget)\n", GREEN, totalSpend, RESET, (totalSpend / budget) * 100, budget);
            System.out.printf("Total Saved: $%s%.2f%s\n", GREEN, totalSaved, RESET);
            System.out.println("--------------------------------------------------");
            System.out.println("Type an " + BOLD + "ID" + RESET + " to skip a game and automatically replace it.");
            System.out.println("Type " + BOLD + "'cart'" + RESET + " to generate the checkout script.");
            System.out.println("Type " + BOLD + "'back'" + RESET + " to return to the main menu.");
            System.out.print("> ");

            String input = scanner.nextLine().trim().toLowerCase();

            if (input.equals("back") || input.isEmpty()) {
                return;
            } else if (input.equals("cart")) {
                generateCartScript();
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

    private static void runOptimizationAlgorithm() {
        for (Game g : basket) g.picked = false;

        int budgetCents = (int) Math.round(budget * 100);
        List<WeightedItem> candidates = new ArrayList<>();

        for (Game g : basket) {
            if (g.skip || g.sale < 0 || g.sale == g.normal) continue;
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

        if (complexity > 0 && complexity <= 10_000_000 && !candidates.isEmpty()) {
            chosen = knapsackExact(candidates, budgetCents);
        } else {
            chosen = knapsackGreedy(candidates, budgetCents);
        }

        for (WeightedItem w : chosen) {
            w.game.picked = true;
        }
    }

    // --- Algorithms ---
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