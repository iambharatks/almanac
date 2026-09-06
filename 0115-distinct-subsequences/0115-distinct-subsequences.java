class Solution {
    int [][] dp;
    public int rec(int n, int m, String s ,String t){
        if(m == 0) return 1;
        if(n == 0) return 0;
        if(dp[n][m] != -1) return dp[n][m];
        int res = 0;
        if(s.charAt(n-1) == t.charAt(m-1)) res += rec(n-1,m-1,s,t);
        return dp[n][m] = res + rec(n-1,m,s,t);
    }
    public int numDistinct(String s, String t) {
        int n = s.length();
        int m = t.length();
        dp = new int[n+1][m+1];
        for(int i = 0 ; i <= n ; i++){
            for(int j = 0 ; j <= m; j++){
                if(i == 0) dp[i][j] = 0;
                if(j == 0) dp[i][j] = 1;
                else dp[i][j] = -1;
            }
        }
        return rec(n,m,s,t);
    }
}