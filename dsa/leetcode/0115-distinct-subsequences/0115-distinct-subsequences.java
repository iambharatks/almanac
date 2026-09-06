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
        for(int i = 0 ; i <= n ; i++) dp[i][0] = 1;
        for(int j = 1 ; j <= m ; j++) dp[0][j] = 0;
        
        for(int i = 1 ; i <= n ; i++){
            for(int j = 1 ; j <= m; j++){
                if(s.charAt(i-1) == t.charAt(j-1)) 
                    dp[i][j] += dp[i-1][j-1];
                dp[i][j] += dp[i-1][j];
            }
        }
        // return rec(n,m,s,t);
        return dp[n][m];
    }
}