class Solution {
    vector<vector<int>> dp;
public:
    int rec(vector<int>& piles, int l, int r){
        if(l > r) return 0;
        if(dp[l][r] != -1) return dp[l][r];
        return dp[l][r] = max(piles[l]-rec(piles,l+1,r),piles[r]-rec(piles,l,r-1));
    }
    bool stoneGame(vector<int>& piles) {
        // maximize(a[l] - rec(l+1,r),a[r]-rec(l,r-1));
        int n = size(piles);
        dp.assign(n,vector<int>(n,-1));
        // for(int i = 0 ; i < n ; i++) dp[i][i] = i;
        int res = rec(piles,0,n-1);
        return res > 0;
    }
};