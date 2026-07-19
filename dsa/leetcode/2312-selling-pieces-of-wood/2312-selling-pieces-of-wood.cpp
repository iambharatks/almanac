class Solution {
public:
    long long sellingWood(int n, int m, vector<vector<int>>& prices) {
        vector<vector<long long>> dp(n+1,vector<long long>(m+1,0));
        for(const auto &p : prices){
            dp[p[0]][p[1]] =(long long) p[2];
        }
        for(int r = 1 ; r <= n ; r++){
            for(int c = 1 ; c <= m ; c++){
                for(int ci = 1 ; ci <= c/2; ci++)
                    dp[r][c] = max(dp[r][c],dp[r][ci]+dp[r][c-ci]);
                for(int ri = 1 ; ri <= r/2; ri++)
                    dp[r][c] = max(dp[r][c],dp[ri][c]+dp[r-ri][c]);
            }
        }
        return dp[n][m];
    }
};