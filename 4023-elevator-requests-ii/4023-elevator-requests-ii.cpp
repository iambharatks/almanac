class Solution {
    vector<vector<vector<long long>>> dp;
public:
    long long rec(int l,int r,bool isLeft, vector<int> &req){
        if(l <= 0 && r >= size(req)-1) return 0;
        long long res = 1e18;
        int n = size(req);
        if(dp[l][r][isLeft] != -1) return dp[l][r][isLeft];
        long long rem = n-(r-l+1);
        if(isLeft){
            if(l > 0)
                res = min(res,abs(req[l]-req[l-1])*rem + rec(l-1,r,isLeft,req));
            if(r < size(req)-1){
                res = min(res,abs(req[r+1]-req[l])*rem + rec(l,r+1,!isLeft,req)); 
            }
        }else{
            if(l > 0) 
                res = min(res,abs(req[r]-req[l-1])*rem + rec(l-1,r,!isLeft,req));
            if(r < size(req)-1){
                res = min(res,abs(req[r+1]-req[r])*rem + rec(l,r+1,isLeft,req)); 
            }
        }
        return dp[l][r][isLeft] = res;
    }
    long long elevatorRequests(int n, int start, vector<int>& req) {
        bool  contains = false;
        for(int i : req){
            if(i == start) {
                contains = true;
                break;
            }
        }
        if(!contains){
            req.push_back(start);
        }
        n = size(req);
        sort(begin(req),end(req));
        dp.assign(n,vector<vector<long long>>(n,vector<long long>(2,1e18)));

        int ind = lower_bound(begin(req),end(req),start)-begin(req);
        // return rec(ind,ind,true,req);
        dp[ind][ind][0] = dp[ind][ind][1] = 0;
        for(int r = ind ; r < n ; r++){
            for(int l = ind; l >= 0; l--){
                if(l == r){ dp[l][r][0] = dp[l][r][1] = 0;
                continue;}
                int l1 = l+1;
                int r1 = r-1;
                long long rem = n-(r-l);
                dp[l][r][1] = min(dp[l+1][r][1]+(req[l+1]-req[l])*rem, dp[l+1][r][0]+(req[r]-req[l])*rem);
                dp[l][r][0] = min(dp[l][r-1][0]+(req[r]-req[r-1])*rem,dp[l][r-1][1]+(req[r]-req[l])*rem);
            }
        }
        return min(dp[0][n-1][0],dp[0][n-1][1]);
    }
};