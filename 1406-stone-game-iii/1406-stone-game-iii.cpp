class Solution {
    vector<int> dp;
public:
    int rec(vector<int> &stone,int l,int n){
        int res = INT_MIN;
        if(l >= n) return 0;
        int cur = 0;
        if(dp[l] != -1) return dp[l];
        for(int i = 0 ; i < 3 && l+i < n;i++){
            cur += stone[l+i];
            res = max(res,cur - rec(stone,l+i+1,n));
        }
        return dp[l] = res;
    }
    string stoneGameIII(vector<int>& stoneValue) {
        int n = size(stoneValue);
        dp.assign(n,-1);
        // int res = rec(stoneValue,0,size(stoneValue));
        vector<int> ndp(n+1);
        for(int l = n-1; l >= 0; l--){
            int curSum = 0;
            ndp[l] = INT_MIN;
            for(int r = 0; r < 3 && l+r< n ; r++){
                curSum += stoneValue[l+r];
                ndp[l] = max(ndp[l],curSum - ndp[l+r+1]);
            }
        }
        int res = ndp[0];
        if(res == 0) return "Tie";
        return res > 0?"Alice":"Bob";
    }
};