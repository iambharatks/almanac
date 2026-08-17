class Solution {
    vector<vector<int>> dp;
public:
    int rec(int l,int r,vector<int> &stones){
        if(l >= r) return 0;
        int left = 0;
        int right = 0;
        if(dp[l][r] != -1) return dp[l][r];
        for(int i =l ; i <= r ; i++) right += stones[i];
        int res = 0;
        for(int i = l ; i < r ; i++){
            left += stones[i];
            right -= stones[i];
            if(left >= right){
                res = max(res,right+rec(i+1,r,stones));
            }
            if(left <= right){
                res = max(res,left+rec(l,i,stones));
            }
        }
        return dp[l][r] = res;
    }
    int stoneGameV(vector<int>& stoneValue) {
        int n = size(stoneValue);
        dp.assign(n,vector<int>(n,-1));
        return rec(0,n-1,stoneValue);
    }
};