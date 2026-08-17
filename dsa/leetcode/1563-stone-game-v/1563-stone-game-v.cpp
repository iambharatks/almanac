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
        dp.assign(n,vector<int>(n,0));
        for(int i = 1 ; i < n ; i++){
            stoneValue[i] += stoneValue[i-1];
        }
        for(int r = 0 ; r < n ; r++){
            for(int l = r-1 ; l >= 0; l--){
                if(l == r){
                    dp[l][r] = 0;
                }
                for(int i = l ; i < r ; i++){
                    int leftSum = stoneValue[i]-((l>0)?stoneValue[l-1]:0);
                    int rightSum = stoneValue[r]-stoneValue[i];
                    if(leftSum >= rightSum){
                        dp[l][r] = max(rightSum + dp[i+1][r],dp[l][r]); 
                    }
                    if(leftSum <= rightSum){
                        dp[l][r] = max(leftSum + dp[l][i],dp[l][r]); 
                    }
                }
            }
        }
        // return rec(0,n-1,stoneValue);
        return dp[0][n-1];
    }
};