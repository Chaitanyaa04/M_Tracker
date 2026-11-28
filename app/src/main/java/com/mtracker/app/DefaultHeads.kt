package com.mtracker.app

/** Icon keys for the built-in heads. Kept here so Room migrations can backfill icons without Compose imports. */
val DEFAULT_HEAD_ICONS: Map<String, String> = mapOf(
    "Food & Dining" to "food", "Groceries" to "grocery", "Auto & Transport" to "car",
    "Bills & Utilities" to "bolt", "Coffee & Snacks" to "cafe", "Vegetables & Fruits" to "grocery",
    "Milk & Dairy" to "grocery", "Gas & Fuel" to "fuel", "Travel" to "flight",
    "Mobile Recharge" to "phone", "Phone & Internet" to "wifi", "Electricity" to "bolt",
    "Water" to "water", "Gas Cylinder" to "gas", "Rent" to "home", "EMI & Loans" to "bank",
    "Shopping" to "shop", "Clothing" to "cloth", "Household" to "home", "Laundry" to "laundry",
    "Entertainment" to "movie", "Online Subscriptions" to "subs", "Personal Care" to "spa",
    "Health & Insurance" to "health", "Medicines" to "meds", "Insurance Premium" to "health",
    "Fitness" to "fitness", "Education" to "school", "Books" to "book", "Stationery" to "pencil",
    "Home & Repairs" to "tools", "Gifts & Donations" to "gift", "Family & Kids" to "kids",
    "Pets" to "pets", "Investments" to "invest", "Taxes & Fees" to "bank", "Bank Charges" to "bank",
    "Other" to "other",
    // Broader defaults for fresh installs; older names above remain mapped for existing history.
    "Local transport" to "car", "Trips & travel" to "flight", "Bills & utilities" to "bolt",
    "Home & rent" to "home", "Personal care" to "spa", "Health" to "health",
    "Subscriptions" to "subs", "Family & kids" to "kids",
    "Salary" to "income", "Stipend" to "income", "Pocket Money" to "income", "Freelance" to "work",
    "Refunds" to "refund", "Cashback" to "reward", "Interest" to "savings", "Dividends" to "reward",
    "Rent Received" to "home", "Gifts" to "gift"
)

fun defaultHeadIcon(name: String, kind: String): String =
    DEFAULT_HEAD_ICONS[name] ?: if (kind == "INCOME") "income" else "tag"
