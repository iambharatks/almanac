class Solution {
    vector<int> dp;
public:
    bool rec(int n){
        int i = sqrt(n);
        if(i*i == n) return dp[n] = true;
        if(dp[n] != -1) return dp[n];
        for(int i = 1 ; i*i <= n ; i++){
            bool res = rec(n-i*i);
            if(!res) return dp[n] = true;
        }
        return dp[n] = false;
    }
    bool winnerSquareGame(int n) {
        // vector<bool> dp(n);
        dp.assign(n+1,-1);
        return rec(n);
    }
};