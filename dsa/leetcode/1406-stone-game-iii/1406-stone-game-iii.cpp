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
        int res = rec(stoneValue,0,size(stoneValue));
        if(res == 0) return "Tie";
        return res > 0?"Alice":"Bob";
    }
};