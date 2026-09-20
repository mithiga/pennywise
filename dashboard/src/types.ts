export type TransactionType = "INCOME" | "EXPENSE" | "CREDIT" | "TRANSFER" | "INVESTMENT";

export type MoneyMap = Record<string, string>;

export interface DashboardTransaction {
  hash: string;
  amount: string;
  currency: string;
  merchantName: string;
  description: string | null;
  category: string;
  transactionType: TransactionType | string;
  dateTime: string;
  bankName: string | null;
  accountNumber: string | null;
  fromAccount: string | null;
  toAccount: string | null;
  excludedFromAnalytics: boolean;
  updatedAt: string | null;
  smsBody: string | null;
  smsSender: string | null;
}

export interface DashboardAccount {
  bankName: string;
  accountLast4: string;
  balance: string | null;
  currency: string;
  alias: string | null;
  isCreditCard: boolean;
  transactionCount: number;
}

export interface DashboardSummary {
  income: MoneyMap;
  expense: MoneyMap;
  accounts: DashboardAccount[];
  recent: DashboardTransaction[];
}

export interface TransactionWrite {
  amount?: string;
  currency?: string;
  merchantName?: string;
  description?: string | null;
  category?: string;
  transactionType?: string;
  dateTime?: string;
  bankName?: string | null;
  accountNumber?: string | null;
  fromAccount?: string | null;
  toAccount?: string | null;
  excludedFromAnalytics?: boolean;
}
