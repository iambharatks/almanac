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
        // dp.assign(n+1,-1);
        // return rec(n);
        vector<bool> dp(n+1,false);
        for(int i = 1; i <= n ; i++){
            int k = (int)sqrt(i);
            if(k*k == i){
                dp[i] = true;
            }
            for(int j = 1 ; j*j <= i; j++){
                if(!dp[i-j*j]){ 
                    dp[i] = true;
                }
            }
        }
        return dp[n];
    }
};